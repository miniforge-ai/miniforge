;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-preparation
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.environment-comparison-bindings :as bindings]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} population-declared? [pin]
  (contains? (:comparison/contract pin) :comparison/replica-contract))

(defn- ^{:stratum 0} payloads-match? [request]
  (and (= (:candidate/hash request) (hash/content-hash (:candidate/policy request)))
       (= (:experiment-pack/hash request) (hash/content-hash (:experiment-pack request)))))

(defn- ^{:stratum 0} rollback-bounds [request]
  (select-keys (get-in request [:comparison/pin :comparison/contract :comparison/replica-contract :rollback])
               [:replicas/min-count :replicas/max-count]))

(defn ^{:stratum 0} observation [projected]
  (let [fingerprint (:environment-fingerprint projected)
        target (:runtime/target projected)
        raw {:kubernetes/verification projected}
        replicas (:comparison/replica-observations projected)]
    {:environment-fingerprint fingerprint
     :runtime/target target
     :environment/raw-observations raw
     :comparison/replica-observations replicas}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} correlated? [declaration request]
  (let [pin (:comparison/pin request)
        contract (:comparison/contract pin)]
    (and (= (:runtime/target request) (:runtime/target declaration))
         (= (:comparison/binding contract)
            (bindings/comparison-binding (:workflow/id declaration) (:runtime/target declaration) request))
         (= (:comparison/hash pin) (hash/content-hash contract))
         (payloads-match? request))))

(defn ^{:stratum 1} capture-context [declaration request]
  (let [target (dissoc declaration :workflow/id)
        bounds (rollback-bounds request)]
    {:kubernetes/declaration target
     :comparison/bounds bounds}))

(comment ::read-only-preparation-is-not-durability-or-execution-authority)
