;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-restoration
  "Compose the existing original-baseline constructor with a current recovery plan and native capture.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} restore-parameters {:opsv/operation :restore-baseline})

(defn ^{:stratum 0} provider-for-plan? [provider plan]
  (let [target (get-in plan [:recovery/binding :runtime/target])]
    (and (= (:kubernetes/context plan) (:kubernetes/context provider))
         (= (:cluster target) (:kubernetes/cluster-id provider))
         (= (:namespace target) (:kubernetes/namespace provider)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} configured-for-plan? [input plan]
  (and (not= (:effect/id input) (get-in plan [:recovery/load :creation-effect/id]))
       (provider-for-plan? (:restoration/provider input) plan)))

(defn ^{:stratum 1} proposal-input [input plan capture]
  (let [native (get-in input [:recovery/input :load])
        selected (select-keys native [:declaration :request :comparison/artifact-ref])
        limits (assoc (:limits native) :effect/id (:effect/id input)
                      :opsv/duration-ms (:recovery/duration-ms plan))]
    (assoc selected :current capture :parameters restore-parameters :limits limits)))

(comment
  (configured-for-plan? {} {})
  (proposal-input {:recovery/input {:load {:limits {}}}} {:recovery/duration-ms 1000} {}))
