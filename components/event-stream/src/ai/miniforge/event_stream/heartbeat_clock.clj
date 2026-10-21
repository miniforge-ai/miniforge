;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-clock
  "Separate wall observations from activation-local monotonic durations.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private nanos-per-millisecond 1000000)

(defn ^{:stratum 0} wall-time [] (java.util.Date.))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} monotonic-ms [] (quot (System/nanoTime) nanos-per-millisecond))

(comment ::heartbeat-clock-domains)
