;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.boundary.results
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.messages :as msg]
            [ai.miniforge.execution-grant.schema :as schema]
            [malli.core :as m]
            [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} explanation [input-schema value]
  (let [explain (me/humanize (m/explain input-schema value))]
    {:explain explain}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} invalid [message-key input-schema value]
  (anomaly/sub-anomaly :invalid-input :anomalies.execution-grant/invalid
                       (msg/t message-key) (explanation input-schema value)))

(defn ^{:stratum 1} invalid-output [value]
  (anomaly/sub-anomaly :fault :anomalies.execution-grant/invalid
                       (msg/t :issuance/output-invalid) (explanation schema/ExecutionGrant value)))

(comment ::preserve-the-public-boundary-error-contract)
