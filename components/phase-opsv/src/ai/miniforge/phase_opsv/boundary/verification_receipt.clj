;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.verification-receipt
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.verification-schema :as schema]
            [ai.miniforge.redaction.interface :as redaction]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} correlate [request observations]
  (when (and (map? request) (map? observations))
    (merge (select-keys request schema/correlation-keys) observations)))

(defn ^{:stratum 0} admit [receipt-schema invalid-key request result]
  (cond
    (not (redaction/supported? result))
    (anomaly/anomaly :invalid-input (msg/ts invalid-key) {})
    (anomaly/anomaly? result)
    (redaction/redact (update result :anomaly/data dissoc
                              :opsv/environment-drift :opsv/drift-publication-failure))
    (not (m/validate receipt-schema result))
    (anomaly/anomaly :invalid-input (msg/ts invalid-key) {})
    (not= (select-keys request schema/correlation-keys) (select-keys result schema/correlation-keys))
    (anomaly/anomaly :conflict (msg/ts :verification/mismatched) {})
    :else result))

(comment ::admission-once-at-the-adapter-boundary)
