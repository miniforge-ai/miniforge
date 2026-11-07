;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-provider
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-http :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as settings]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-effect-schema :as native]
            [ai.miniforge.phase-opsv.kubernetes-load-binding :as binding]
            [ai.miniforge.phase-opsv.kubernetes-load-outcome :as outcome]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-load-schema :as load-schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

(def ^{:stratum 0 :private true} valid-load?
  (m/validator [:tuple load-schema/Limits load-schema/LoadWorkerSettings]))

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :load/invalid-provider) {}))

(defn- ^{:stratum 0} diagnostics []
  (effect/provider-dispatch-diagnostics (msg/ts :load/unconfirmed) (msg/ts :load/invalid-provider)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admit [runtime record dispatch]
  (anomaly/let-ok [_bounded (reporting/admit [runtime record])]
    (if (valid-arguments? [runtime record dispatch])
      (settings/load-settings runtime native/Settings [:kubernetes/node-pool-label :kubernetes/max-resources])
      (invalid))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create! [runtime record dispatch]
  (anomaly/let-ok [configured (admit runtime record dispatch)]
    (let [input (binding/input record)]
      (if (and (valid-load? [(:limits input) (:runtime input)]) (binding/matches? configured record input))
        (effect/call-with-provider-dispatch! dispatch
          (partial http/send! configured record transport/created-status outcome/report outcome/refused) (diagnostics))
        (invalid)))))

(comment ::required-trusted-dispatch-no-auto-retry-release-or-reconciliation-by-name)
