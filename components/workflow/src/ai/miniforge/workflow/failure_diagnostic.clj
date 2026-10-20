;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.failure-diagnostic
  "Read anomaly values from live execution or the checkpoint timestamp encoding."
  (:require [ai.miniforge.anomaly.interface :as anomaly])
  (:import [java.time Instant]
           [java.time.format DateTimeParseException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} decode-checkpoint-time [value]
  (when (string? (:anomaly/at value))
    (try
      (assoc value :anomaly/at (Instant/parse (:anomaly/at value)))
      (catch DateTimeParseException _ nil))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} as-anomaly [value]
  (let [candidate (if (anomaly/any-anomaly? value) value (decode-checkpoint-time value))]
    (when (anomaly/any-anomaly? candidate) candidate)))

(comment ::checkpoint-diagnostic-boundary)
