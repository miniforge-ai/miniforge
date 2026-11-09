;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial-observation
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-verification-preparation :as native]
            [ai.miniforge.phase-opsv.kubernetes-baseline-recovery :as baseline]
            [ai.miniforge.phase-opsv.kubernetes-replica-proposal :as model]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refused []
  (anomaly/anomaly :conflict (msg/ts :replicas/native-trial-refused) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe! [input plan retained]
  (if-not (model/configured-for-plan? (:trial/provider input) (:effect/id input) plan)
    (refused)
    (let [load (get-in input [:recovery/input :load])
          provider (dissoc (:trial/provider input) :kubernetes/context)]
      (anomaly/let-ok [observed (native/prepare! provider (:declaration load) (:request load))
                      _original (baseline/compare-original (get-in load [:request :comparison/pin]) retained observed)]
        observed))))

(comment ::fresh-original-bound-observation-is-not-trial-authority)
