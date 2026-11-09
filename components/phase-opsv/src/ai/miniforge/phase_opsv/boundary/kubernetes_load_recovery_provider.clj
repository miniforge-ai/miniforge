;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-recovery-provider
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as settings]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-effect-schema :as native]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid [message-key]
  (anomaly/anomaly :invalid-input (msg/ts message-key) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} admit [runtime record dispatch valid-arguments? invalid-key]
  (anomaly/let-ok [_bounded (reporting/admit [(:input runtime) record])]
    (if (valid-arguments? [runtime record dispatch])
      (settings/load-settings (get-in runtime [:input :ownership/runtime :provider]) native/Settings
                              [:kubernetes/node-pool-label :kubernetes/max-resources])
      (invalid invalid-key))))

(comment ::one-shared-representation-schema-and-provider-settings-ingress-for-recovery-effects)
