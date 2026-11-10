;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial-effect
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-settings :as settings]
            [ai.miniforge.phase-opsv.boundary.kubernetes-replica-provider :as native]
            [ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial :as trial]
            [ai.miniforge.phase-opsv.kubernetes-replica-proposal :as proposal]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} submit! [input record dispatch]
  (anomaly/let-ok [configured (settings/load! (:trial/provider input))]
    (native/patch! configured record dispatch)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} patch! [ctx input record dispatch]
  (anomaly/let-ok [current (trial/prepare! ctx input)]
    (if (proposal/same-proposal? current record)
      (submit! input record dispatch)
      (anomaly/anomaly :conflict (msg/ts :replicas/native-trial-refused) {}))))

(comment ::fresh-exact-proposal-before-the-governed-provider-dispatch)
