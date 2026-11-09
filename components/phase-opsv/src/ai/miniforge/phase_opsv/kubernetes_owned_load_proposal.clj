;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-owned-load-proposal
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.governed-experiment-proposal :as proposal]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} native-input [input duration-ms]
  (-> (:recovery/input input)
      :load
      (assoc-in [:limits :effect/id] (:effect/id input))
      (assoc-in [:limits :opsv/duration-ms] duration-ms)))

(defn- ^{:stratum 0} bound-intent [plan owned intent]
  (let [uid (get-in owned [:load/pod "metadata" "uid"])
        resource (assoc (get-in plan [:recovery/load :resource]) :uid uid)
        target (get-in plan [:recovery/binding :runtime/target])
        digest (hash/content-hash (:kubernetes/request intent))]
    (assoc intent :effect/class :effect/load-test :runtime/target target
           :opsv/resources [resource] :opsv/payload-hash digest)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} proposal [ctx input plan owned intent duration-ms]
  (proposal/proposal ctx (native-input input duration-ms) (bound-intent plan owned intent)))

(comment ::one-owned-load-scope-factory-with-operation-specific-duration-never-authority)
