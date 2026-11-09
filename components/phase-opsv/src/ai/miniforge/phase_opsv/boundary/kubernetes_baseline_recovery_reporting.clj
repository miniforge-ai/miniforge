;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-baseline-recovery-reporting
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.artifact-confirmation :as confirmation]
            [ai.miniforge.phase-opsv.artifact-model :as artifacts]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-baseline-recovery :as model]
            [ai.miniforge.phase-opsv.kubernetes-baseline-recovery-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]
            [ai.miniforge.phase-opsv.verification-drift :as drift]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-drift! [ctx compared]
  (if (and (get-in compared [:anomaly/data :opsv/environment-drift]) (nil? (context/stream ctx)))
    (assoc-in compared [:anomaly/data :opsv/drift-publication-failure]
              (anomaly/anomaly :unavailable (msg/ts :event/publication-failed) {}))
    (drift/record! ctx compared)))

(defn- ^{:stratum 0} drift-sensitive? [compared]
  (let [details (:anomaly/data compared)]
    (or (some? (:comparison/reason details))
        (and (= :conflict (:anomaly/type compared))
             (contains? details :kubernetes/expected-uid)
             (contains? details :kubernetes/observed-identity)))))

(defn- ^{:stratum 0} retain! [ctx input admitted original observed compared]
  (let [report (model/report input admitted original observed compared)
        output {:opsv/baseline-recovery report}]
    (anomaly/let-ok [value (artifacts/record ctx output schema/material)]
      (let [protected-output (assoc output :opsv/baseline-recovery (:artifact/content value))]
        (confirmation/publish-with-exception-handling ctx value (artifacts/begin-publication protected-output))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} comparison [input original observed]
  (let [request (get-in input [:recovery/input :load :request])
        compared (model/compare-original (:comparison/pin request) original observed)]
    (if (drift-sensitive? compared)
      (drift/comparison-mismatch compared request)
      compared)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish! [ctx input admitted original observed]
  (let [compared (comparison input original observed)]
    (anomaly/let-ok [safe (reporting/protect {:comparison/result compared})]
      (let [recorded (record-drift! ctx (:comparison/result safe))]
        (retain! ctx input admitted original observed recorded)))))

(comment ::protect-before-drift-delivery-and-retain-delivery-failures-with-comparison)
