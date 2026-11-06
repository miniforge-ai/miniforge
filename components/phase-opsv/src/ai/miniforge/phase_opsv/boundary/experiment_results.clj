;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.experiment-results
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.resource-cleanup.interface :as cleanup]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} invalid [key] (anomaly/anomaly :invalid-input (msg/ts key) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failed! [failure]
  (if (cleanup/fatal-cause failure)
    (cleanup/after-failure! failure (constantly nil))
    (invalid :experiment/invalid-preflight)))

(comment ::sanitized-boundary-failures-preserve-fatal-causes)
