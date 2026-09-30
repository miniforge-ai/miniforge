;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.lifecycle-outcome
  "Canonical shared-phase result construction."
  (:require [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} empty-metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} phase-result [output]
  (cond-> {:status (if (anomaly/anomaly? output) :error :success)
           :output output :metrics empty-metrics}
    (anomaly/anomaly? output)
    (assoc :error {:message (:anomaly/message output) :data (:anomaly/data output)})))
