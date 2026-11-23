;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-replica-request
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.verification-receipt :as receipts]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-replica-request :as request]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.verification-preparation-schema :as preparation]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} invalid [] (anomaly/anomaly :invalid-input (msg/ts :replicas/invalid-request) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare [declaration value baseline current parameters]
  (let [arguments [declaration value baseline current parameters]]
    (anomaly/let-ok [_bounded (reporting/admit arguments)]
      (if-not (valid-arguments? arguments)
        (invalid)
        (anomaly/let-ok [observed (receipts/admit preparation/Receipt :replicas/invalid-request value baseline)]
          (request/prepare declaration value observed current parameters))))))

(comment ::validate-native-shapes-once-at-the-public-request-boundary)
