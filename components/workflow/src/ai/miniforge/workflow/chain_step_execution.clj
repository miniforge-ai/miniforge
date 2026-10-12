;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-step-execution
  "Own child-run identity across step lifecycle effects."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.boundary.chain-step :as child]
            [ai.miniforge.workflow.chain-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} emit! [opts constructor & args]
  (when-let [stream (:event-stream opts)]
    (let [identity {:workflow/id (:workflow-id opts)}
          event (apply constructor stream (concat args [identity]))]
      (events/publish! stream event))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} terminal! [opts chain-id step index result]
  (if (outcome/completed? result)
    (emit! opts events/chain-step-completed chain-id (:step/id step) index)
    (emit! opts events/chain-step-failed chain-id (:step/id step) index (:execution/error result))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} step! [chain-id step index previous input opts]
  (let [child-opts (assoc opts :workflow-id (random-uuid))]
    (emit! child-opts events/chain-step-started chain-id (:step/id step) index (:step/workflow-id step))
    (let [result (child/execute step previous input child-opts)]
      (terminal! child-opts chain-id step index result)
      result)))

(comment
  ::child-run-lifecycle)
