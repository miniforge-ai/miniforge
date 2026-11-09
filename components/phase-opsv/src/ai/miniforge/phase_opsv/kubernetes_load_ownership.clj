;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-ownership
  "Correlate durable creation and live identity; never adopt a Pod by name or labels."
  (:require [ai.miniforge.phase-opsv.kubernetes-load-binding :as binding]
            [ai.miniforge.phase-opsv.kubernetes-load-schema :as load]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-schema :as schema]
            [ai.miniforge.phase-opsv.verification-schema :as verification]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-creation? (m/validator schema/Creation))

(def ^{:stratum 0 :private true} valid-pod? (m/validator schema/Pod))

(def ^{:stratum 0 :private true} valid-worker?
  (m/validator [:tuple load/Limits load/LoadWorkerSettings]))

(defn- ^{:stratum 0} plan-bound? [plan record]
  (let [intent (:recovery/load plan)
        attempt (:recovery/binding plan)
        proposal (:effect/proposal record)
        scope (:opsv/experiment proposal)
        resource (get-in record [:effect/observed :kubernetes/resource])]
    (and (= (:creation-effect/id intent) (:effect/id record) (:effect/id proposal))
         (= (:workflow/id attempt) (:workflow-run/id proposal))
         (= (select-keys attempt verification/correlation-keys) (select-keys scope verification/correlation-keys))
         (= (:runtime/target attempt) (:runtime/target scope))
         (= (get-in plan [:recovery/restore :resource :name]) (:kubernetes/deployment-name scope))
         (= (:kubernetes/context plan) (:kubernetes/context scope))
         (= (:comparison/artifact-ref plan) (:comparison/artifact-ref scope))
         (= (:comparison/hash plan) (:comparison/hash scope))
         (= (:creation-payload/hash intent) (:opsv/payload-hash scope))
         (= [(:resource intent)] (:opsv/resources scope))
         (= (:resource intent) (assoc resource :uid nil)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observed? [record pod]
  (let [resource (get-in record [:effect/observed :kubernetes/resource])
        metadata (get pod "metadata")]
    (and (valid-pod? pod)
         (= (:name resource) (get metadata "name"))
         (= (:namespace resource) (get metadata "namespace"))
         (= (:uid resource) (get metadata "uid")))))

(defn ^{:stratum 1} recorded? [settings plan record]
  (and (valid-creation? record)
       (plan-bound? plan record)
       (let [input (binding/input record)]
         (and (valid-worker? [(:limits input) (:runtime input)])
              (binding/matches? settings record input)))))

(comment ::changed-worker-state-is-not-adopted-as-approved-load-or-termination)
