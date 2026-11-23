;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-stop-provider
  "API surface class 1: one explicit conditional patch after current intent and ownership reconstruction."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-http :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-stop :as stop]
            [ai.miniforge.phase-opsv.boundary.kubernetes-owned-load-provider :as admission]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-outcome :as outcome]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-provider-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} diagnostics []
  (effect/provider-dispatch-diagnostics (msg/ts :load/stop-unconfirmed) (msg/ts :load/invalid-stop-provider)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} patch! [runtime record dispatch]
  (anomaly/let-ok [configured (admission/admit runtime record dispatch valid-arguments? :load/invalid-stop-provider)
                  _canonical (admission/reconstruct! runtime record stop/prepare! :load/stop-request-refused)]
    (effect/call-with-provider-dispatch! dispatch
      (partial http/send! configured record #{transport/success-status} outcome/report outcome/refused) (diagnostics))))

(comment
  (patch! {} {} identity))
