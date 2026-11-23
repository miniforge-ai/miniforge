;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-preparation-fixtures
  (:require [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.replica-comparison-fixtures :as replicas]
            [ai.miniforge.phase-opsv.replica-contract-fixtures :as contract]
            [ai.miniforge.phase-opsv.verification-schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} request []
  (select-keys (environment/verification-binding) schema/correlation-keys))

(defn ^{:stratum 0} observations []
  (let [plan (contract/plan)
        fingerprint (:comparison/baseline plan)
        target (get-in plan [:comparison/binding :runtime/target])
        expected (get-in plan [:comparison/replica-contract :rollback])
        replica (replicas/replica expected "baseline-instance-uid")]
    {:environment-fingerprint fingerprint
     :runtime/target target
     :comparison/replica-observations [replica]}))

(defn ^{:stratum 0} record-verification [calls request]
  (swap! calls conj [:verify request])
  request)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record-preparation [calls request]
  (swap! calls conj [:prepare request])
  (phase/verification-preparation-receipt request (observations)))

(comment ::preparation-values-have-no-authority-or-freshness-proof)
