;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.probe-deadline
  "Monotonic cooperative admission and remaining wait time for one native proof.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} nanos-per-ms
  "The unit conversion, not an operational timeout."
  1000000)

(defn ^{:stratum 0} live? [until]
  (and (not (.isInterrupted (Thread/currentThread))) (< (System/nanoTime) until)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} after-ms [budget]
  (+ (System/nanoTime) (* budget nanos-per-ms)))

(defn ^{:stratum 1} remaining-ms [until]
  (let [remaining (max 0 (- until (System/nanoTime)))]
    (quot (+ remaining (dec nanos-per-ms)) nanos-per-ms)))

(comment (remaining-ms (System/nanoTime)))
