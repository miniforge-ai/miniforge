;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-cleanup-provider
  "API surface class 1: one exact noncascading deletion after current quiescent ownership."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-http :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-cleanup :as cleanup]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-recovery-provider :as admission]
            [ai.miniforge.phase-opsv.kubernetes-load-cleanup-outcome :as outcome]
            [ai.miniforge.phase-opsv.kubernetes-load-cleanup-provider-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} diagnostics []
  (effect/provider-dispatch-diagnostics (msg/ts :load/cleanup-unconfirmed) (msg/ts :load/invalid-cleanup-provider)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} delete! [runtime record dispatch]
  (anomaly/let-ok [configured (admission/admit runtime record dispatch valid-arguments? :load/invalid-cleanup-provider)
                  prepared (cleanup/prepare! (:context runtime) (:input runtime))]
    (if (= prepared (:effect/proposal record))
      (effect/call-with-provider-dispatch! dispatch
        (partial http/send! configured record #{transport/success-status transport/accepted-status}
                 outcome/report outcome/refused) (diagnostics))
      (anomaly/anomaly :conflict (msg/ts :load/cleanup-request-refused) {}))))

(comment ::independent-current-effect-admission-exact-recovery-grant-and-pinned-commit-remain-mandatory)
