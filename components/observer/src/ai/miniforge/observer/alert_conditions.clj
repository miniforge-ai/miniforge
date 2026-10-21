;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.alert-conditions
  "Pure threshold comparisons; no clock reads or state mutation.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} phase-gap [threshold gap]
  (when (and gap threshold (> gap threshold))
    {:gap-ms gap :threshold-ms threshold}))

(defn ^{:stratum 0} timeout [threshold now [call-id {:keys [started-at alerted?]}]]
  (let [elapsed (- now started-at)]
    (when (and threshold (not alerted?) (> elapsed threshold))
      {:call-id call-id :elapsed-ms elapsed :threshold-ms threshold})))

(defn- ^{:stratum 0} outcome-summary [outcomes]
  (when (seq outcomes)
    (let [failures (count (remove true? outcomes))
          total (count outcomes)]
      {:error-rate (double (/ failures total)) :failure-count failures :total-count total})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} error-rate [threshold outcomes]
  (let [summary (outcome-summary outcomes)]
    (when (and summary threshold (> (:error-rate summary) threshold))
      (assoc summary :threshold-pct threshold))))

(comment ::strict-alert-thresholds)
