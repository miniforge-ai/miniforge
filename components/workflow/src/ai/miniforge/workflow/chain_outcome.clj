;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-outcome
  "Normalize child outcomes and construct chain projections in one place."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} error-message [result]
  (let [error (or (:execution/error result) (:anomaly/message result) (:message result))]
    (if (string? error) error (messages/t :chain/child-incomplete))))

(defn ^{:stratum 0} step-result [chain-id step index result]
  {:step/id (:step/id step)
   :step/workflow-id (:step/workflow-id step)
   :step/execution-id (:execution/id result)
   :step/status (:execution/status result)
   :step/output (:execution/output result)
   :step/chain-id chain-id
   :step/chain-index index})

(defn- ^{:stratum 0} result-data [result]
  (if (map? result) result {}))

(defn ^{:stratum 0} chain-result [definition duration results status]
  (let [step-count (count (:chain/steps definition))]
    {:chain/id (:chain/id definition)
     :chain/status status
     :chain/step-results results
     :chain/step-count step-count
     :chain/duration-ms duration}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failure [result]
  {:execution/status :failed
   :execution/error (error-message result)
   :execution/cause result})

(defn ^{:stratum 1} normalize [result]
  (if (and (not (anomaly/any-anomaly? result)) (phase/succeeded? result)) result
      (assoc (result-data result)
             :execution/status :failed :execution/error (error-message result))))

(comment
  (normalize nil))
