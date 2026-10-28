;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.changed-files
  "The changed-files analysis scope: judge an explicit set of {:path :content}
   entries instead of scanning the repo. A run gates only the files it
   touched, and an in-scope file is judged in full.

   Two shapes: one rule across the changed files (`analyze-rule-on-files`),
   and the batched form of one call per file across many rules
   (`analyze-rules-on-files`).

   Layer 0: one rule over the changed files; one file against its applicable rules
   Layer 1: the batched per-file fold across all rules (over Layer 0)"
  (:require
   [ai.miniforge.semantic-analyzer.judge :as judge]
   [ai.miniforge.semantic-analyzer.limits :as limits]
   [ai.miniforge.semantic-analyzer.parsing :as parsing]
   [ai.miniforge.semantic-analyzer.selection :as selection]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} analyze-rule-on-files
  "Analyze an explicit set of changed files against one rule, instead of
   scanning the whole repo. `files` is a vector of {:path :content}. Filters to
   files matching the rule's globs and under the size limit, runs the judge per
   file, and returns the same result shape as `core/analyze-rule`.

   This is the changed-files scope: a run gates only the files it touched, and
   an in-scope file is judged in full (every violation in it must be fixed)."
  [llm-client complete-fn rule files]
  (let [start-ms (System/currentTimeMillis)
        globs    (get-in rule [:rule/applies-to :file-globs] ["**/*"])
        eligible (vec (->> files
                           (filter (fn [{:keys [path content]}]
                                     (and (string? path) (string? content)
                                          (selection/file-under-size-limit? content)
                                          (selection/file-matches-globs? path globs))))
                           (take limits/max-files-per-rule)))
        viols    (vec (mapcat (fn [{:keys [path content]}]
                                (judge/analyze-file llm-client complete-fn rule path content))
                              eligible))
        end-ms   (System/currentTimeMillis)]
    {:rule/id        (get rule :rule/id)
     :violations     viols
     :files-analyzed (count eligible)
     :duration-ms    (- end-ms start-ms)
     :status         :completed}))

(defn- ^{:stratum 0} file-rule-violations
  "Judge ONE changed file against the rules whose globs match it. Returns the
   vector of canonical violations (possibly empty) when a judge call was made, or
   `nil` when the file is skipped — bad shape, or no rule applies — so no LLM
   call is spent on it."
  [llm-client complete-fn rules {:keys [path content] :as file}]
  (when (selection/judgeable-file? file)
    (let [applicable (filterv #(selection/file-matches-globs?
                                path (get-in % [:rule/applies-to :file-globs] ["**/*"]))
                              rules)]
      (when (seq applicable)
        (judge/analyze-file-batched llm-client complete-fn applicable path content)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} analyze-rules-on-files
  "Batched changed-files judge: ONE LLM call per file, covering all `rules` whose
   globs match that file (and that are under the size limit). `files` is a vector
   of {:path :content}, `rules` a vector of rule maps. Returns
   {:by-rule {rule-id [violation...]} :files-analyzed int :calls int
    :duration-ms int :status :completed}.

   This collapses the per-rule-per-file judge (analyze-rule-on-files run once per
   rule) into one call per file: the rule pack is sent once per file, not once
   per (rule, file)."
  [llm-client complete-fn rules files]
  (let [start-ms (System/currentTimeMillis)]
    (loop [fs (seq files), by-rule {}, analyzed 0, calls 0]
      (if-let [file (first fs)]
        (let [viols (file-rule-violations llm-client complete-fn rules file)]
          (cond
            ;; skipped (bad shape or no applicable rule) — no call spent
            (nil? viols) (recur (next fs) by-rule analyzed calls)
            ;; judged — one call made; fold its violations (possibly none)
            :else        (recur (next fs) (parsing/index-by-rule by-rule viols)
                                (inc analyzed) (inc calls))))
        {:by-rule        by-rule
         :files-analyzed analyzed
         :calls          calls
         :duration-ms    (- (System/currentTimeMillis) start-ms)
         :status         :completed}))))
