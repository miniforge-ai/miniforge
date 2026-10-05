;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain
  "Sequential workflow chains: only completed children admit dependent execution."
  (:require [ai.miniforge.clock.interface :as clock]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.chain-bindings :as bindings]
            [ai.miniforge.workflow.chain-execution :as execution]
            [ai.miniforge.workflow.chain-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} resolve-binding bindings/resolve-binding)

(def ^{:stratum 0} resolve-bindings bindings/resolve-bindings)

(def ^{:stratum 0} emit! execution/emit!)

(defn- ^{:stratum 0} advance! [chain-id input opts state step]
  (let [results (:results state)
        index (count results)
        previous (get-in state [:last-result :execution/output])
        result (execution/step! chain-id step index previous input opts)
        projection (outcome/step-result chain-id step index result)
        next-results (conj results projection)
        next-state {:results next-results :last-result result}]
    (if (outcome/completed? result) next-state (reduced next-state))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-chain
  "Execute sequentially, recording terminal outcomes for loading and child failures.
   Returns chain status, step projections, count, and elapsed duration."
  [definition input opts]
  (let [started (clock/now-ms)
        step-opts (execution/options opts)
        advance (partial advance! (:chain/id definition) input step-opts)]
    (emit! opts events/chain-started (:chain/id definition) (count (:chain/steps definition)))
    (let [state (reduce advance {:results [] :last-result nil} (:chain/steps definition))]
      (execution/finish! definition started state opts))))

(comment
  (resolve-binding :chain/input.task nil {:task "example"}))
