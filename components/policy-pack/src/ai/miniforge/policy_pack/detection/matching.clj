;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.policy-pack.detection.matching
  "Pattern-matching and terraform-plan-parsing primitives shared by the
   content-scan/diff-analysis/plan-output/ast-analysis detectors. Split
   out of `ai.miniforge.policy-pack.detection` (rule 210: the combined
   namespace measured 6 real layers, max 3) — same approach as the
   mdc-compiler split, miniforge#1729-#1743, and the workflow-runner
   split, miniforge#1662.

   Layer 0: pattern coercion, pattern/detection-config extraction,
     terraform plan-output line parsing — pure, no same-file dependents
   Layer 1: find-matches (line-oriented), any-pattern-matches-multiline?,
     plan-resource-counts (over Layer 0)
   Layer 2: any-pattern-matches? (over Layer 1)"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Pattern matching utilities
(defn ^{:stratum 0} ensure-pattern
  "Convert string or regex to compiled pattern."
  [p]
  (cond
    (instance? java.util.regex.Pattern p) p
    (string? p) (re-pattern p)
    :else nil))

(defn ^{:stratum 0} extract-patterns
  "Extract pattern(s) from detection config.
   Returns vector of patterns."
  [detection]
  (let [{:keys [pattern patterns]} detection]
    (cond
      (seq patterns) patterns
      pattern [pattern]
      :else [])))

;; Plan output detection
(defn ^{:stratum 0} parse-plan-resources
  "Parse terraform plan output to extract resource changes.
   Returns vector of {:action :resource :details}."
  [plan-output]
  (when plan-output
    (let [;; Match lines like:
          ;; "# aws_route.main will be destroyed"
          ;; "# aws_subnet.private[0] must be replaced"
          ;; "# aws_vpc.main will be created"
          ;; "  -/+ aws_route.main (tainted)"
          action-patterns
          [{:pattern #"#\s+(\S+)\s+will be (created|destroyed|updated)"
            :extractor (fn [[_ resource action]]
                         {:resource resource
                          :action (case action
                                    "created" :create
                                    "destroyed" :destroy
                                    "updated" :update
                                    (keyword action))})}
           {:pattern #"#\s+(\S+)\s+must be (replaced)"
            :extractor (fn [[_ resource _action]]
                         {:resource resource
                          :action :replace})}
           {:pattern #"^\s*(-/\+|~|\+|-)\s+(\S+)"
            :extractor (fn [[_ symbol resource]]
                         {:resource resource
                          :action (case symbol
                                    "-/+" :replace
                                    "~" :update
                                    "+" :create
                                    "-" :destroy
                                    :unknown)})}]]
      (->> (str/split-lines plan-output)
           (mapcat (fn [line]
                     (keep (fn [{:keys [pattern extractor]}]
                             (when-let [match (re-find pattern line)]
                               (assoc (extractor match)
                                      :line line)))
                           action-patterns)))
           (distinct)
           vec))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} find-matches
  "Find all matches of a pattern in content.
   Returns vector of {:match string :line int :column int :context string}."
  [pattern content context-lines]
  (when-let [pat (ensure-pattern pattern)]
    (let [lines (str/split-lines content)
          context-lines (or context-lines 2)]
      (->> lines
           (map-indexed vector)
           (keep (fn [[idx line]]
                   (when-let [match (re-find pat line)]
                     (let [match-str (if (string? match) match (first match))
                           start (max 0 (- idx context-lines))
                           end (min (count lines) (+ idx context-lines 1))
                           context-vec (subvec (vec lines) start end)]
                       {:match match-str
                        :line (inc idx)  ; 1-indexed
                        :column (when-let [idx (str/index-of line match-str)]
                                  (inc idx))
                        :context (str/join "\n" context-vec)}))))
           vec))))

;; Content scan detection
(defn ^{:stratum 1} any-pattern-matches-multiline?
  "Like `any-pattern-matches?` but matches each pattern against the WHOLE content
   rather than line-by-line, for rules whose pattern must span lines (e.g. a k8s
   `resources:` block followed by `limits:`). Returns a one-element matches vec
   (with the match's `:line`/`:column` and a bounded `:context` — never the whole
   file, to avoid payload bloat and leaking unrelated content) on the first hit,
   or nil."
  [patterns content]
  (some (fn [p]
          (when-let [pat (ensure-pattern p)]
            (let [matcher (re-matcher pat content)]
              (when (.find matcher)
                (let [start     (.start matcher)
                      match-str (.group matcher)
                      before    (subs content 0 start)
                      last-nl   (str/last-index-of before "\n")
                      match-cap (if (> (count match-str) 200)
                                  (str (subs match-str 0 200) "…")
                                  match-str)]
                  [{:match   match-str
                    :line    (inc (count (re-seq #"\n" before)))
                    :column  (inc (- start (if last-nl (inc last-nl) 0)))
                    :context match-cap}])))))
        patterns))

(defn ^{:stratum 1} plan-resource-counts
  "Aggregate resource change counts from terraform plan output.
   Returns {:creates int :updates int :destroys int}."
  [plan-output]
  (let [resources (parse-plan-resources plan-output)]
    (reduce (fn [acc {:keys [action]}]
              (case action
                :create  (update acc :creates inc)
                :destroy (update acc :destroys inc)
                :update  (update acc :updates inc)
                :replace (-> acc (update :creates inc) (update :destroys inc))
                acc))
            {:creates 0 :updates 0 :destroys 0}
            resources)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} any-pattern-matches?
  "Check if any of the patterns match the content.
   Returns the first matching pattern's matches, or nil."
  [patterns content context-lines]
  (when (seq patterns)
    (some (fn [p]
            (let [matches (find-matches p content context-lines)]
              (when (seq matches)
                matches)))
          patterns)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Parse plan resources
  (parse-plan-resources
   "# aws_vpc.main will be created
    # aws_subnet.private[0] will be destroyed
    # aws_route.public must be replaced
      -/+ aws_route.main (tainted)")

  :leave-this-here)
