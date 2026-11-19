;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.runtime.deadline)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} nanos-per-ms
  "Monotonic time conversion; nanoseconds in a millisecond."
  1000000)

(defn ^{:stratum 0} remaining-ns [until]
  (max 0 (- until (System/nanoTime))))

(defn ^{:stratum 0} expired? [until]
  (>= (System/nanoTime) until))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ceil-ms [remaining-ns]
  (quot (+ remaining-ns (dec nanos-per-ms)) nanos-per-ms))

(defn ^{:stratum 1} after-ms [budget-ms]
  (+ (System/nanoTime) (* budget-ms nanos-per-ms)))
