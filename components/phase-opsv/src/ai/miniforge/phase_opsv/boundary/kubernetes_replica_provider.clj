;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-replica-provider
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.boundary.kubernetes-replica-http :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as settings]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-replica-binding :as binding]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-replica-wire-schema :as wire]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :replicas/invalid-provider) {}))

(defn- ^{:stratum 0} diagnostics []
  (effect/provider-dispatch-diagnostics (msg/ts :replicas/unconfirmed) (msg/ts :replicas/invalid-provider)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admit [runtime record dispatch]
  (anomaly/let-ok [_bounded (reporting/admit [runtime record])]
    (if (valid-arguments? [runtime record dispatch])
      (settings/load-settings runtime wire/Settings [:kubernetes/node-pool-label :kubernetes/max-resources])
      (invalid))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} patch! [runtime record dispatch]
  (anomaly/let-ok [configured (admit runtime record dispatch)]
    (if (binding/matches? configured record)
      (effect/call-with-provider-dispatch! dispatch (partial http/patch! configured record) (diagnostics))
      (invalid))))

(comment ::required-trusted-dispatch-no-default-admission-or-experiment-activation)
