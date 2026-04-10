;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.reliability.slo
  "SLO target checking per workflow tier per N1 §5.5.3.

   Layer 0: Default targets, pure checking functions")

;------------------------------------------------------------------------------ Layer 0
;; Default SLO targets per tier

(def default-targets
  "Default SLO targets. nil means advisory-only (no enforcement).
   For SLI-6 (unknown failure rate), the target is a maximum (lower is better)."
  {:SLI-1 {:best-effort nil :standard 0.85 :critical 0.95}
   :SLI-3 {:best-effort nil :standard 0.90 :critical 0.95}
   :SLI-5 {:best-effort nil :standard 0.95 :critical 0.99}
   :SLI-6 {:best-effort nil :standard 0.10 :critical 0.02}})

(def ^:private inverted-slis
  "SLIs where lower is better (the target is a ceiling, not a floor)."
  #{:SLI-6})

;------------------------------------------------------------------------------ Layer 0
;; SLO checking (pure)

(defn get-target
  "Get the SLO target for an SLI at a given tier. Returns nil if advisory-only."
  ([sli-name tier] (get-target sli-name tier default-targets))
  ([sli-name tier targets]
   (get-in targets [sli-name tier])))

(defn check-slo
  "Check whether an SLI result meets its SLO target.

   Returns: {:breached? bool :sli/name :slo/target :slo/actual :slo/tier :slo/window}
   Returns nil if the SLI has no target for this tier (advisory-only)."
  ([sli-result tier] (check-slo sli-result tier default-targets))
  ([sli-result tier targets]
   (let [{:keys [sli/name sli/value sli/window]} sli-result
         target (get-target name tier targets)]
     (when target
       (let [breached? (if (inverted-slis name)
                         (> value target)    ; for inverted SLIs, actual > target = breach
                         (< value target))]  ; for normal SLIs, actual < target = breach
         {:breached? breached?
          :sli/name name
          :slo/target target
          :slo/actual value
          :slo/tier tier
          :slo/window window})))))

(defn check-all-slos
  "Check all SLI results against SLO targets for a given tier.
   Returns only the results that have targets (excludes advisory-only)."
  ([sli-results tier] (check-all-slos sli-results tier default-targets))
  ([sli-results tier targets]
   (->> sli-results
        (map #(check-slo % tier targets))
        (filter some?)
        vec)))

(defn breached-slos
  "Filter to only the SLOs that are breached."
  [slo-checks]
  (filter :breached? slo-checks))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (check-slo {:sli/name :SLI-1 :sli/value 0.80 :sli/window :7d} :standard)
  ;; => {:breached? true :sli/name :SLI-1 :slo/target 0.85 :slo/actual 0.80 ...}

  (check-slo {:sli/name :SLI-1 :sli/value 0.92 :sli/window :7d} :standard)
  ;; => {:breached? false ...}

  ;; SLI-6 is inverted: actual > target = breach
  (check-slo {:sli/name :SLI-6 :sli/value 0.15 :sli/window :7d} :standard)
  ;; => {:breached? true ...} because 0.15 > 0.10 target

  :leave-this-here)
