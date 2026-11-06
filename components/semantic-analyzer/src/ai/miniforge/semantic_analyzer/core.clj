;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.core
  "The repo-scan analysis scope: judge every file in a repo that falls in a
   rule's path scope, one rule at a time, with a per-rule timeout and
   bounded parallelism.

   The changed-files scope lives in `changed-files`; both rest on the
   per-file judge calls in `judge`.

   Layer 0: one rule over the whole repo
   Layer 1: the same, bounded by a timeout (over Layer 0)
   Layer 2: many rules in parallel batches (over Layer 1)"
  (:require
   [ai.miniforge.semantic-analyzer.judge :as judge]
   [ai.miniforge.semantic-analyzer.limits :as limits]
   [ai.miniforge.semantic-analyzer.selection :as selection]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} analyze-rule
  [llm-client complete-fn repo-path rule]
  (let [start-ms (System/currentTimeMillis)
        files    (selection/select-files-for-rule repo-path rule)
        viols    (vec (mapcat #(judge/analyze-file-in-repo llm-client complete-fn repo-path rule %)
                              files))
        end-ms   (System/currentTimeMillis)]
    {:rule/id        (get rule :rule/id)
     :violations     viols
     :files-analyzed (count files)
     :duration-ms    (- end-ms start-ms)
     :status         :completed}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} analyze-rule-with-timeout
  "Run analyze-rule with a timeout. Returns result or timeout marker."
  [llm-client complete-fn repo-path rule timeout-ms]
  (let [fut (future (analyze-rule llm-client complete-fn repo-path rule))]
    (try
      (let [result (deref fut timeout-ms ::timeout)]
        (if (= result ::timeout)
          (do (future-cancel fut)
              {:rule/id      (get rule :rule/id)
               :violations   []
               :files-analyzed 0
               :duration-ms  timeout-ms
               :status       :timeout})
          result))
      (catch Exception e
        {:rule/id      (get rule :rule/id)
         :violations   []
         :files-analyzed 0
         :duration-ms  0
         :status       :error
         :error        (.getMessage e)}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} analyze-rules-parallel
  [llm-client complete-fn repo-path rules opts]
  (let [timeout-ms   (get opts :timeout-ms limits/default-rule-timeout-ms)
        max-parallel (get opts :max-parallel 4)
        partitions   (partition-all max-parallel rules)]
    (->> partitions
         (mapcat (fn [batch]
                   (let [futures (mapv #(future (analyze-rule-with-timeout
                                                 llm-client complete-fn repo-path % timeout-ms))
                                      batch)]
                     (mapv deref futures))))
         vec)))
