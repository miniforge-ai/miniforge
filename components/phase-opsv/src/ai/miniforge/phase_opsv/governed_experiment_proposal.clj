;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-experiment-proposal
  (:require [ai.miniforge.phase-opsv.verification-schema :as verification]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} experiment-binding [input prepared]
  (let [request (:request input)]
    (merge (dissoc (:limits input) :effect/id)
           (select-keys request verification/correlation-keys)
           (select-keys prepared [:runtime/target :opsv/operation :opsv/payload-hash :opsv/resources])
           {:environment/class :staging
            :kubernetes/deployment-name (get-in input [:declaration :kubernetes/deployment-name])
            :comparison/artifact-ref (:comparison/artifact-ref input)
            :comparison/hash (get-in request [:comparison/pin :comparison/hash])})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} proposal [ctx input prepared]
  {:workflow-run/id (:execution/id ctx)
   :effect/id (get-in input [:limits :effect/id])
   :effect/class (:effect/class prepared)
   :opsv/experiment (experiment-binding input prepared)
   :kubernetes/request (:kubernetes/request prepared)})

(comment ::one-canonical-proposal-data-factory-not-grant-issuance-or-admission)
