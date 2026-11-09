;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-container-termination
  "Reject absent, synthetic zero, malformed and reversed provider termination times."
  (:require [slingshot.slingshot :refer [try+]])
  (:import [java.time Instant]
           [java.time.format DateTimeParseException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} positive-time [value]
  (when (string? value)
    (try+
      (let [instant (Instant/parse value)]
        (when (.isAfter instant Instant/EPOCH) instant))
      (catch DateTimeParseException _ nil))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ordered-times? [value]
  (let [started (positive-time (get value "startedAt"))
        finished (positive-time (get value "finishedAt"))]
    (and started finished (not (.isAfter ^Instant started ^Instant finished)))))
