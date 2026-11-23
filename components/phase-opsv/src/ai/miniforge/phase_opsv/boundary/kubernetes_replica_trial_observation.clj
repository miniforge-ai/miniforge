;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial-observation
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-verification-preparation :as native]
            [ai.miniforge.phase-opsv.boundary.kubernetes-trial-comparison-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-baseline-recovery :as baseline]
            [ai.miniforge.phase-opsv.kubernetes-replica-proposal :as model]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refused []
  (anomaly/anomaly :conflict (msg/ts :replicas/native-trial-refused) {}))

(defn- ^{:stratum 0} capture! [input]
  (let [load (get-in input [:recovery/input :load])
          provider (dissoc (:trial/provider input) :kubernetes/context)]
    (anomaly/let-ok [acquired (native/observe! provider (:declaration load) (:request load))]
      (baseline/observation acquired))))

(defn- ^{:stratum 0} checked! [ctx input admitted retained observed]
  (let [pin (get-in input [:recovery/input :load :request :comparison/pin])
        compared (baseline/compare-original pin retained observed)]
    (if (anomaly/anomaly? compared)
      (reporting/refuse! ctx input admitted retained observed)
      observed)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe! [ctx input admitted retained]
  (if-not (model/configured-for-plan? (:trial/provider input) (:effect/id input) (:recovery/plan admitted))
    (refused)
    (checked! ctx input admitted retained (capture! input))))

(comment ::fresh-original-bound-observation-is-not-trial-authority)
