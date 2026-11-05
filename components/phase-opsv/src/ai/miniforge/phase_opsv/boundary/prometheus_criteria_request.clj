;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.prometheus-criteria-request
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.prometheus-criteria-schema :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} admit [configuration]
  (anomaly/let-ok [_finite (reporting/admit configuration)]
    (if (m/validate schema/Configuration configuration)
      configuration
      (anomaly/anomaly :invalid-input (msg/ts :measurement/invalid-request) {}))))

(comment ::complete-declaration-before-any-query)
