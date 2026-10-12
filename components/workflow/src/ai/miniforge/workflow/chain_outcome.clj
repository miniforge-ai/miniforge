;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-outcome
  "Normalize child outcomes and construct chain projections in one place."
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure-value? [result]
  (and (map? result)
       (or (contains? result :anomaly/type) (contains? result :anomaly/category))))

(defn ^{:stratum 0} with-child-identity [error id]
  (let [path (if (contains? error :anomaly/type) [:anomaly/data :workflow/id] [:workflow/id])]
    (assoc-in error path id)))

(defn- ^{:stratum 0} error-message [result]
  (let [error (or (:execution/error result) (:anomaly/message result) (:message result))]
    (if (string? error) error (messages/t :chain/child-incomplete))))

(defn ^{:stratum 0} step-result [chain-id step index result]
  (cond-> {:step/id (:step/id step)
           :step/workflow-id (:step/workflow-id step)
           :step/execution-id (:execution/id result)
           :step/status (:execution/status result)
           :step/output (:execution/output result)
           :step/chain-id chain-id
           :step/chain-index index}
    (contains? result :execution/bindings) (assoc :step/bindings (:execution/bindings result))))

(defn- ^{:stratum 0} result-data [result]
  (if (map? result) result {}))

(defn ^{:stratum 0} chain-result [definition duration results status]
  (let [step-count (count (:chain/steps definition))]
    (merge (:chain/run-record definition)
           {:chain/id (:chain/id definition)
            :chain/status status
            :chain/step-results results
            :chain/step-count step-count
            :chain/duration-ms duration})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} completed? [result]
  (and (= :completed (:execution/status result))
       (not (failure-value? result))
       (phase/succeeded? result)))

(defn ^{:stratum 1} failure [result]
  {:execution/status :failed
   :execution/error (error-message result)
   :execution/cause result})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} normalize [result]
  (if (completed? result) result
      (assoc (result-data result)
             :execution/status :failed :execution/error (error-message result))))

(comment
  (normalize nil))
