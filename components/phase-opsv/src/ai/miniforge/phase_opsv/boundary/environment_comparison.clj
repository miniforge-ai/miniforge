;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.environment-comparison
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.environment-comparison :as comparison]
            [ai.miniforge.phase-opsv.environment-comparison-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} pin [plan]
  (if (m/validate schema/Plan plan)
    (comparison/pin plan)
    (anomaly/anomaly :invalid-input (msg/ts :verification/invalid-environment-contract) {})))

(comment ::public-environment-comparison-boundary)
