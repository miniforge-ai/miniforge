;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-partition
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-membership :as membership]
            [ai.miniforge.phase-opsv.kubernetes-inventory-partition :as partition]
            [ai.miniforge.phase-opsv.kubernetes-workload-inventory-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} admit [raw]
  (if-not (m/validate schema/Observation raw)
    (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-inventory) {})
    (or (partition/project raw) (membership/mismatch))))

(comment ::pure-captured-partition-not-freshness-configuration-health-or-authority)
