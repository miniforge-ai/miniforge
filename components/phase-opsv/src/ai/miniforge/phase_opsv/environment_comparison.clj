;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-comparison
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.messages :as msg]))

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

(defn- ^{:stratum 0} mismatch [reason expected observed]
  (anomaly/anomaly :conflict (msg/ts :verification/environment-contract-mismatch)
                   {:comparison/reason reason
                    :comparison/expected expected
                    :comparison/observed observed}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} compare-observation
  [{:comparison/keys [pin expected-hash binding stage observed]}]
  (let [contract (:comparison/contract pin)
        stored-hash (:comparison/hash pin)
        actual-hash (hash/content-hash contract)
        expected-binding (:comparison/binding contract)
        expected (expected-fingerprint contract stage)]
    (cond
      (not= expected-hash stored-hash actual-hash)
      (mismatch :contract-mismatch expected-hash [stored-hash actual-hash])
      (not= binding expected-binding)
      (mismatch :binding-mismatch expected-binding binding)
      (not= expected observed)
      (mismatch :environment-drift expected observed)
      :else observed)))

(comment ::pure-environment-comparison)
