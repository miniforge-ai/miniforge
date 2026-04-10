;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.training-capture.quality
  "Quality score computation for training examples per learning.spec §2.2.

   Pure functions — no side effects.

   Layer 0: Quality score computation
   Layer 1: Example labeling")

;------------------------------------------------------------------------------ Layer 0
;; Quality score computation

(defn compute-quality-score
  "Compute quality score from observable signals.

   Arguments:
     feedback - map with keys:
       :validation-result    - :passed | :failed | :partial
       :iterations-to-pass   - int, inner loop iterations needed
       :escalated?           - bool, escalated to human
       :human-override?      - bool, human overrode agent decision
       :phase-succeeded?     - bool, did outer loop phase complete
       :production-incident? - bool, caused issues in prod

   Returns: float 0.0-1.0"
  [{:keys [validation-result iterations-to-pass escalated?
           human-override? phase-succeeded? production-incident?]}]
  (let [base-score (case validation-result
                     :passed  1.0
                     :partial 0.5
                     :failed  0.0
                     0.0)
        iteration-penalty (* 0.1 (max 0 (dec (or iterations-to-pass 1))))
        escalation-penalty (if escalated? 0.3 0)
        override-penalty (if human-override? 0.2 0)
        incident-penalty (if production-incident? 0.5 0)
        phase-bonus (if phase-succeeded? 0.1 0)]
    (-> base-score
        (- iteration-penalty)
        (- escalation-penalty)
        (- override-penalty)
        (- incident-penalty)
        (+ phase-bonus)
        (max 0.0)
        (min 1.0))))

;------------------------------------------------------------------------------ Layer 1
;; Example labeling

(defn label-example
  "Classify training example type based on quality score and feedback.

   Returns: :positive | :negative | :corrected | :hard-positive | :ambiguous

   Per learning.spec §2.3:
     :positive      - quality >= 0.8, no corrections
     :negative      - quality < 0.3, clear failure mode
     :corrected     - human provided correction (highest-value)
     :hard-positive - passed after multiple iterations
     :ambiguous     - 0.3 <= quality < 0.8, needs labeling"
  [quality-score {:keys [human-correction iterations-to-pass]}]
  (cond
    human-correction                              :corrected
    (and (>= quality-score 0.8)
         (> (or iterations-to-pass 1) 2))         :hard-positive
    (>= quality-score 0.8)                        :positive
    (< quality-score 0.3)                         :negative
    :else                                         :ambiguous))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (compute-quality-score {:validation-result :passed
                          :iterations-to-pass 1
                          :phase-succeeded? true})
  ;; => 1.0 (capped)

  (compute-quality-score {:validation-result :passed
                          :iterations-to-pass 4
                          :escalated? false
                          :phase-succeeded? true})
  ;; => 0.8

  (label-example 0.9 {}) ;; => :positive
  (label-example 0.1 {}) ;; => :negative
  (label-example 0.5 {}) ;; => :ambiguous
  (label-example 0.9 {:human-correction "fix"}) ;; => :corrected
  (label-example 0.85 {:iterations-to-pass 3})  ;; => :hard-positive

  :leave-this-here)
