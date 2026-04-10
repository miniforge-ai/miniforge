;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.training-capture.quality
  "Quality score computation for training examples per learning.spec §2.2.

   Pure functions — no side effects.

   Layer 0: Constants from config
   Layer 1: Quality score computation
   Layer 2: Example labeling"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Constants from config

(def ^:private config
  (-> (io/resource "config/training-capture/defaults.edn") slurp edn/read-string))

(def ^:private iteration-penalty-per-extra  (:iteration-penalty-per-extra config))
(def ^:private escalation-penalty           (:escalation-penalty config))
(def ^:private human-override-penalty       (:human-override-penalty config))
(def ^:private production-incident-penalty  (:production-incident-penalty config))
(def ^:private phase-success-bonus          (:phase-success-bonus config))
(def ^:private positive-min                 (get-in config [:label-thresholds :positive-min]))
(def ^:private negative-max                 (get-in config [:label-thresholds :negative-max]))
(def ^:private hard-positive-min-iterations (get-in config [:label-thresholds :hard-positive-min-iterations]))

(def ^:private base-scores
  "Base quality scores by validation result."
  {:passed  1.0
   :partial 0.5
   :failed  0.0})

;------------------------------------------------------------------------------ Layer 1
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
  (let [base        (get base-scores validation-result 0.0)
        iters       (if (some? iterations-to-pass) iterations-to-pass 1)
        extra-iters (max 0 (dec iters))]
    (-> base
        (- (* iteration-penalty-per-extra extra-iters))
        (- (if escalated? escalation-penalty 0))
        (- (if human-override? human-override-penalty 0))
        (- (if production-incident? production-incident-penalty 0))
        (+ (if phase-succeeded? phase-success-bonus 0))
        (max 0.0)
        (min 1.0))))

;------------------------------------------------------------------------------ Layer 2
;; Example labeling

(defn label-example
  "Classify training example type based on quality score and feedback.

   Returns: :positive | :negative | :corrected | :hard-positive | :ambiguous"
  [quality-score {:keys [human-correction iterations-to-pass]}]
  (cond
    human-correction                                         :corrected
    (and (>= quality-score positive-min)
         (> (if (some? iterations-to-pass) iterations-to-pass 1)
            (dec hard-positive-min-iterations)))              :hard-positive
    (>= quality-score positive-min)                          :positive
    (< quality-score negative-max)                           :negative
    :else                                                    :ambiguous))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (compute-quality-score {:validation-result :passed
                          :iterations-to-pass 1
                          :phase-succeeded? true})
  ;; => 1.0

  (label-example 0.9 {}) ;; => :positive
  (label-example 0.1 {}) ;; => :negative

  :leave-this-here)
