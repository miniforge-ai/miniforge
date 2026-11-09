;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-proposal)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} provider-for-plan? [provider plan]
  (let [target (get-in plan [:recovery/binding :runtime/target])]
    (and (= (:kubernetes/context plan) (:kubernetes/context provider))
         (= (:cluster target) (:kubernetes/cluster-id provider))
         (= (:namespace target) (:kubernetes/namespace provider)))))

(defn ^{:stratum 0} proposal-input [input capture parameters]
  (let [load (get-in input [:recovery/input :load])
        selected (select-keys load [:declaration :request :comparison/artifact-ref])
        limits (assoc (:limits load) :effect/id (:effect/id input))]
    (assoc selected :current capture :parameters parameters :limits limits)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} configured-for-plan? [provider id plan]
  (and (not= id (get-in plan [:recovery/load :creation-effect/id]))
       (provider-for-plan? provider plan)))

(comment
  (provider-for-plan? {} {}))
