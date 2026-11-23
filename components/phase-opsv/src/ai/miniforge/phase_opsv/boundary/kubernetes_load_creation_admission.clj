;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-creation-admission
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-acquisition :as acquisition]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-target :as target]
            [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as settings]
            [ai.miniforge.phase-opsv.kubernetes-effect-binding :as binding]
            [ai.miniforge.phase-opsv.kubernetes-effect-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-load-request :as proposal]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} configured [plan overrides]
  (anomaly/let-ok [value (settings/load-settings overrides schema/Settings
                                               [:kubernetes/node-pool-label :kubernetes/max-resources])]
    (let [scope (assoc (:recovery/binding plan) :kubernetes/context (:kubernetes/context plan))]
      (if (binding/configured-target? value scope) value (admission/refused)))))

(defn ^{:stratum 0} prepare! [ready input]
  (anomaly/let-ok [intent (proposal/prepare (:context ready) (get-in input [:recovery/input :load])
                                         (:comparison/original ready))]
    (let [planned (get-in ready [:recovery/plan :recovery/load])]
      (if (and (= (:creation-effect/id planned) (:effect/id intent))
               (= (:creation-payload/hash planned) (get-in intent [:opsv/experiment :opsv/payload-hash])))
        intent
        (admission/refused)))))

(defn- ^{:stratum 0} observe-target! [roots value until]
  (target/observe! value until roots))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} readiness! [runtime input]
  (anomaly/let-ok [ready (admission/readiness! runtime input)
                  roots (target/original (:comparison/original ready)
                                         (get-in ready [:recovery/plan :recovery/binding :runtime/target]))
                  observed (acquisition/observe! (partial configured (:recovery/plan ready)) (:creation/provider input)
                                                (partial observe-target! roots))]
    (assoc ready :target/observation observed)))

(comment
  (readiness! {} {}))
