;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-execution
  "Chain lifecycle effects composed around normalized child outcomes."
  (:require [ai.miniforge.clock.interface :as clock]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.boundary.chain-step :as child]
            [ai.miniforge.workflow.chain-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} emit! [opts constructor & args]
  (when-let [stream (:event-stream opts)]
    (events/publish! stream (apply constructor stream args))))

(defn ^{:stratum 0} options [opts]
  (if (:workflow-run/correlation-id opts) opts
      (assoc opts :workflow-run/correlation-id (random-uuid))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} terminal-step! [opts chain-id step index result]
  (if (outcome/completed? result)
    (emit! opts events/chain-step-completed chain-id (:step/id step) index)
    (emit! opts events/chain-step-failed chain-id (:step/id step) index (:execution/error result))))

(defn ^{:stratum 1} finish! [definition started {:keys [results last-result]} opts]
  (let [duration (clock/elapsed-since started)
        status (if (or (nil? last-result) (outcome/completed? last-result)) :completed :failed)
        result (outcome/chain-result definition duration results status)]
    (if (= :completed status)
      (emit! opts events/chain-completed (:chain/id definition) duration (:chain/step-count result))
      (emit! opts events/chain-failed (:chain/id definition)
             (:step/id (peek results)) (:execution/error last-result)))
    result))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} step! [chain-id step index previous input opts]
  (emit! opts events/chain-step-started chain-id (:step/id step) index (:step/workflow-id step))
  (let [result (child/execute step previous input opts)]
    (terminal-step! opts chain-id step index result)
    result))

(comment
  (options {}))
