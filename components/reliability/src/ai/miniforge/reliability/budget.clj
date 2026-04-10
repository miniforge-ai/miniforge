;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.reliability.budget
  "Error budget computation per N1 §5.5.4.

   Error budgets represent remaining tolerance for failures before
   the SLO is breached over a rolling window.

   Layer 0: Constants
   Layer 1: Pure computation functions"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:private defaults
  (-> (io/resource "config/reliability/defaults.edn") slurp edn/read-string))

(def ^:const default-critical-threshold
  "Budget fraction below which the budget is considered critical."
  (:budget-critical-threshold defaults))

;------------------------------------------------------------------------------ Layer 1
;; Budget computation

(defn compute-error-budget
  "Compute error budget for an SLI over a window.

   Arguments:
     sli-value   - current SLI value (0.0-1.0)
     slo-target  - SLO target for the tier
     inverted?   - true if lower-is-better (e.g., SLI-6 unknown failure rate)

   Returns:
     {:error-budget/remaining double  ; 0.0-1.0 fraction of budget remaining
      :error-budget/burn-rate double} ; current burn rate (1.0 = nominal)

   When remaining is 0.0, the budget is exhausted.
   Burn-rate > 1.0 means the budget is being consumed faster than it should."
  [sli-value slo-target inverted?]
  (if inverted?
    ;; Inverted: target is a ceiling (e.g., unknown rate < 0.10)
    ;; Budget = how much room we have before hitting the ceiling
    (let [headroom (- slo-target sli-value)  ; positive = budget remaining
          budget-total slo-target
          remaining (if (pos? budget-total)
                      (max 0.0 (min 1.0 (/ headroom budget-total)))
                      0.0)
          burn-rate (if (pos? budget-total)
                      (/ sli-value budget-total)
                      0.0)]
      {:error-budget/remaining remaining
       :error-budget/burn-rate burn-rate})
    ;; Normal: target is a floor (e.g., success rate >= 0.85)
    ;; Budget = how much we can miss before breaching target
    (let [error-rate (- 1.0 sli-value)
          error-budget-total (- 1.0 slo-target)  ; max allowed errors
          remaining (if (pos? error-budget-total)
                      (max 0.0 (min 1.0 (/ (- error-budget-total error-rate)
                                            error-budget-total)))
                      0.0)
          burn-rate (if (pos? error-budget-total)
                      (/ error-rate error-budget-total)
                      0.0)]
      {:error-budget/remaining remaining
       :error-budget/burn-rate burn-rate})))

;------------------------------------------------------------------------------ Layer 0
;; Predicates

(defn budget-exhausted?
  "Returns true if the error budget remaining is at or below zero."
  [budget]
  (<= (:error-budget/remaining budget) 0.0))

(defn budget-critical?
  "Returns true if the error budget remaining is below the threshold.
   Default threshold: 0.25 (25% remaining)."
  ([budget] (budget-critical? budget default-critical-threshold))
  ([budget threshold]
   (< (:error-budget/remaining budget) threshold)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; SLI-1 at 0.90 with 0.85 target: healthy budget
  (compute-error-budget 0.90 0.85 false)
  ;; => {:error-budget/remaining 0.666... :error-budget/burn-rate 0.666...}

  ;; SLI-1 at 0.80 with 0.85 target: budget exhausted
  (compute-error-budget 0.80 0.85 false)
  ;; => {:error-budget/remaining 0.0 :error-budget/burn-rate 1.333...}

  ;; SLI-6 (inverted) at 0.05 with 0.10 target: healthy
  (compute-error-budget 0.05 0.10 true)
  ;; => {:error-budget/remaining 0.5 :error-budget/burn-rate 0.5}

  :leave-this-here)
