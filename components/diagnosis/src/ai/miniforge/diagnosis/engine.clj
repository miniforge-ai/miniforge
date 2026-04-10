;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.diagnosis.engine
  "Root-cause inference from correlated symptom clusters.

   Takes correlated signal clusters and produces diagnostic hypotheses
   with suggested improvement types.

   Layer 0: Diagnosis logic (pure functions)")

;------------------------------------------------------------------------------ Layer 0
;; Improvement type inference

(defn- infer-improvement-type
  "Infer the most likely improvement type from a correlation cluster."
  [{:keys [correlation/type correlation/signals]}]
  (let [types (set (map :signal/type signals))
        has-heuristic? (some :signal/affected-heuristic signals)]
    (cond
      (and has-heuristic? (contains? types :high-iteration-count))
      :prompt-refinement

      (contains? types :recurring-failure)
      :rule-addition

      (contains? types :quality-regression)
      :prompt-refinement

      (contains? types :slo-breach)
      :threshold-adjustment

      :else
      :threshold-adjustment)))

(defn- infer-affected-heuristic
  "Infer which heuristic is most likely affected."
  [{:keys [correlation/signals]}]
  (or (some :signal/affected-heuristic signals)
      ;; If no explicit heuristic, infer from failure class
      (when-let [failure-class (some #(get-in % [:signal/evidence :failure-class]) signals)]
        (case failure-class
          :failure.class/agent-error :agent-prompt/implementer
          :failure.class/task-code   :agent-prompt/tester
          :failure.class/policy      :gate-threshold/policy
          :failure.class/timeout     :threshold/timeout
          :failure.class/resource    :threshold/budget
          nil))))

;------------------------------------------------------------------------------ Layer 0
;; Diagnosis

(defn diagnose
  "Generate diagnostic hypotheses from correlated symptom clusters.

   Arguments:
     correlations - vector of correlation maps from correlator/correlate-symptoms

   Returns: vector of diagnosis maps
     {:diagnosis/id uuid
      :diagnosis/hypothesis string
      :diagnosis/confidence float
      :diagnosis/affected-heuristic keyword
      :diagnosis/suggested-improvement-type keyword
      :diagnosis/evidence-ids [...]
      :diagnosis/created-at inst}"
  [correlations]
  (->> correlations
       (mapv (fn [correlation]
               {:diagnosis/id (random-uuid)
                :diagnosis/hypothesis (:correlation/hypothesis correlation)
                :diagnosis/confidence (:correlation/confidence correlation)
                :diagnosis/affected-heuristic (infer-affected-heuristic correlation)
                :diagnosis/suggested-improvement-type (infer-improvement-type correlation)
                :diagnosis/signals (:correlation/signals correlation)
                :diagnosis/created-at (java.util.Date.)}))
       (sort-by :diagnosis/confidence >)
       vec))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (diagnose
   [{:correlation/signals [{:signal/type :recurring-failure
                             :signal/evidence {:failure-class :failure.class/timeout :count 5}}]
     :correlation/type :failure-pattern
     :correlation/confidence 0.6
     :correlation/hypothesis "Recurring timeout failures"}])

  :leave-this-here)
