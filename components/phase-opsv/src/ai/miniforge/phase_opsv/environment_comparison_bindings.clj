;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-comparison-bindings
  (:require [ai.miniforge.phase-opsv.environment-expectation :as expectation]
            [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} same-cluster? [plan]
  (= (get-in plan [:comparison/baseline :cluster])
     (get-in plan [:comparison/binding :runtime/target :cluster])))

(defn- ^{:stratum 0} replica-expectation-bound? [plan [stage expected]]
  (let [fingerprint (expectation/fingerprint plan stage)
        target (get-in plan [:comparison/binding :runtime/target])
        pools (set (:node-pools fingerprint))
        allowed (set (:replica/node-pools expected))]
    (and (= target (:replica/target expected))
         (= (:image-digests fingerprint) (:replica/image-digests expected))
         (set/subset? allowed pools))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} replicas-consistent? [plan]
  (if-let [contract (:comparison/replica-contract plan)]
    (every? (partial replica-expectation-bound? plan) contract)
    true))

(comment ::schema-level-correlation-of-declared-expectations)
