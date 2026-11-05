;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.prometheus-criteria-request
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.prometheus-settings :as settings]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.prometheus-criteria :as criteria]
            [ai.miniforge.phase-opsv.prometheus-criteria-schema :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :measurement/invalid-request) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admit-settings [configuration]
  (anomaly/let-ok [resolved (settings/load-settings (:measurement/prometheus configuration))]
    (if (criteria/within-endpoint-cap? configuration resolved)
      (assoc configuration :measurement/prometheus resolved)
      (invalid))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} admit [configuration]
  (anomaly/let-ok [_finite (reporting/admit configuration)]
    (if (m/validate schema/Configuration configuration)
      (admit-settings configuration)
      (invalid))))

(comment ::complete-declaration-before-any-query)
