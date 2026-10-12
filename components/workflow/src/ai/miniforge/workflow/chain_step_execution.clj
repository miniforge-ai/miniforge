;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-step-execution
  "Own child-run identity across step lifecycle effects."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.boundary.chain-step :as child]
            [ai.miniforge.workflow.chain-publication :as publication]
            [ai.miniforge.workflow.chain-step-publication :as terminal]
            [ai.miniforge.workflow.chain-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} emission-failure [opts error]
  (let [id (:workflow-id opts)
        attributed (outcome/with-child-identity error id)]
    (assoc (outcome/failure attributed)
           :execution/id id :execution/emission-failure attributed)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} execute-started! [opts chain-id step index previous input]
  (let [result (child/execute step previous input opts)
        receipt (terminal/terminal! opts chain-id step index result)]
    (if (outcome/failure-value? receipt) (emission-failure opts receipt) result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} step! [chain-id step index previous input opts]
  (let [child-opts (assoc opts :workflow-id (random-uuid))
        receipt (publication/emit-step! child-opts events/chain-step-started chain-id (:step/id step) index (:step/workflow-id step))]
    (if (outcome/failure-value? receipt) (emission-failure child-opts receipt)
        (execute-started! child-opts chain-id step index previous input))))

(comment
  ::child-run-lifecycle)
