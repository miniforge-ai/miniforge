;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.improvement.proposal
  "Improvement proposal generation from diagnoses per learning.spec §5.

   Layer 0: Proposal generation (pure functions)")

;------------------------------------------------------------------------------ Layer 0
;; Proposal generation

(defn generate-proposal
  "Generate an improvement proposal from a diagnosis.

   Currently supports:
     :threshold-adjustment - Modify numeric thresholds
     :rule-addition        - Add new heuristic rule

   Arguments:
     diagnosis - diagnosis map from diagnosis engine

   Returns: improvement proposal map"
  [diagnosis]
  (let [{:keys [diagnosis/suggested-improvement-type
                diagnosis/affected-heuristic
                diagnosis/hypothesis
                diagnosis/confidence
                diagnosis/id]} diagnosis]
    {:improvement/id (random-uuid)
     :improvement/type (or suggested-improvement-type :threshold-adjustment)
     :improvement/target affected-heuristic
     :improvement/status :proposed
     :improvement/rationale hypothesis
     :improvement/confidence confidence
     :improvement/evidence {:diagnosis-id id
                            :signals (:diagnosis/signals diagnosis)}
     :improvement/created-at (java.util.Date.)}))

(defn generate-proposals
  "Generate improvement proposals from a vector of diagnoses.
   Only generates proposals for diagnoses with sufficient confidence."
  [diagnoses & [{:keys [min-confidence] :or {min-confidence 0.3}}]]
  (->> diagnoses
       (filter #(>= (or (:diagnosis/confidence %) 0) min-confidence))
       (mapv generate-proposal)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (generate-proposal {:diagnosis/id (random-uuid)
                       :diagnosis/confidence 0.7
                       :diagnosis/hypothesis "Recurring timeouts"
                       :diagnosis/affected-heuristic :threshold/timeout
                       :diagnosis/suggested-improvement-type :threshold-adjustment})

  :leave-this-here)
