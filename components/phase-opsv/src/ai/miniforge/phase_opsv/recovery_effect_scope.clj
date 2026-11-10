;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-effect-scope
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-scope :as load]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} plan-bound? [proposal plan]
  (let [scope (:opsv/experiment proposal)
        binding (:recovery/binding plan)
        expected (dissoc binding :workflow/id)
        payload (:kubernetes/request proposal)]
    (and (= :staging (:environment/class scope))
         (= (:workflow/id binding) (:workflow-run/id proposal))
         (= expected (select-keys scope (keys expected)))
         (= (:comparison/artifact-ref plan) (:comparison/artifact-ref scope))
         (= (:comparison/hash plan) (:comparison/hash scope))
         (= (:kubernetes/context plan) (:kubernetes/context scope))
         (= (:recovery/duration-ms plan) (:opsv/duration-ms scope))
         (= (hash/content-hash payload) (:opsv/payload-hash scope)))))

(defn- ^{:stratum 0} restoration? [proposal plan]
  (and (= :effect/deploy (:effect/class proposal))
       (= [(get-in plan [:recovery/restore :resource])]
          (get-in proposal [:opsv/experiment :opsv/resources]))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} operation-matches? [proposal plan owned]
  (case (get-in proposal [:opsv/experiment :opsv/operation])
    :restore-baseline (restoration? proposal plan)
    (:stop-load :cleanup-load) (load/matches? proposal plan owned)
    false))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} allowed? [proposal plan owned]
  (and (plan-bound? proposal plan) (operation-matches? proposal plan owned)))

(comment ::shared-plan-correlation-with-operation-specific-current-native-preconditions)
