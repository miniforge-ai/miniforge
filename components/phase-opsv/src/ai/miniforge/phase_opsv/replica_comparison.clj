;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.replica-comparison
  (:require [ai.miniforge.phase-opsv.environment-comparison-failure :as failure]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private exact-replica-fields
  "Acquisition-established values unchanged by replica replacement."
  [:replica/target :replica/template-hash :replica/image-digests])

(defn- ^{:stratum 0} duplicate-identities [replicas]
  (let [ids (mapv :replica/id replicas)
        total (count ids)
        unique-count (count (distinct ids))]
    (when (not= total unique-count)
      (failure/mismatch :duplicate-replica unique-count total))))

(defn- ^{:stratum 0} population-bounds [expected replicas]
  (let [lower (:replicas/min-count expected)
        upper (:replicas/max-count expected)
        observed (count replicas)]
    (when-not (<= lower observed upper)
      (failure/mismatch :replica-count-drift [lower upper] observed))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} replica-mismatch [expected replica]
  (let [invariants (select-keys expected exact-replica-fields)
        observed (select-keys replica exact-replica-fields)
        pools (set (:replica/node-pools expected))
        placement (:replica/node-pool replica)]
    (cond
      (not= invariants observed) (failure/mismatch :replica-drift invariants observed)
      (not (contains? pools placement))
      (failure/mismatch :replica-placement-drift (:replica/node-pools expected) placement)
      :else nil)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} compare-population [expected replicas observation]
  (or (duplicate-identities replicas)
      (population-bounds expected replicas)
      (some (partial replica-mismatch expected) replicas)
      observation))

(comment ::population-admission-is-not-scaling-stability)
