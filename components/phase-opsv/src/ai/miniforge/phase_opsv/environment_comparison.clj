;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-comparison
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.environment-comparison-failure :as failure]
            [ai.miniforge.phase-opsv.replica-comparison :as replicas]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} pin [plan]
  (let [digest (hash/content-hash plan)]
    {:comparison/contract plan
     :comparison/hash digest}))

(defn- ^{:stratum 0} expected-fingerprint [contract stage]
  (let [baseline (:comparison/baseline contract)]
    (case stage
      :candidate (merge baseline (:comparison/changes contract))
      :rollback baseline)))

(defn- ^{:stratum 0} admit-population [contract stage observations fingerprint]
  (if-let [expected (get-in contract [:comparison/replica-contract stage])]
    (replicas/compare-population expected observations fingerprint)
    fingerprint))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} compare-observation
  [{:comparison/keys [pin expected-hash binding stage observed replica-observations]}]
  (let [contract (:comparison/contract pin)
        stored-hash (:comparison/hash pin)
        actual-hash (hash/content-hash contract)
        expected-binding (:comparison/binding contract)
        expected (expected-fingerprint contract stage)]
    (cond
      (not= expected-hash stored-hash actual-hash)
      (failure/mismatch :contract-mismatch expected-hash [stored-hash actual-hash])
      (not= binding expected-binding)
      (failure/mismatch :binding-mismatch expected-binding binding)
      (not= expected observed)
      (failure/mismatch :environment-drift expected observed)
      :else (admit-population contract stage replica-observations observed))))

(comment ::pure-environment-comparison)
