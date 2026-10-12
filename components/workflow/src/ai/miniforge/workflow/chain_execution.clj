;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-execution
  "Chain lifecycle effects composed around normalized child outcomes."
  (:require [ai.miniforge.clock.interface :as clock]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.chain-step-execution :as child]
            [ai.miniforge.workflow.chain-publication :as publication]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.chain-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} emit! publication/emit!)

(defn ^{:stratum 0} options [opts]
  (if (:workflow-run/correlation-id opts) opts
      (assoc opts :workflow-run/correlation-id (random-uuid))))

(def ^{:stratum 0} step! child/step!)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} fail! [definition results error opts]
  (emit! opts events/chain-failed (:chain/id definition) (:step/id (peek results)) error))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} finish! [definition started {:keys [results last-result]} opts]
  (let [duration (clock/elapsed-since started)
        status (if (or (nil? last-result) (outcome/completed? last-result)) :completed :failed)
        result (outcome/chain-result definition duration results status)
        receipt (if (= :completed status)
                  (emit! opts events/chain-completed (:chain/id definition) duration (:chain/step-count result))
                  (fail! definition results (:execution/error last-result) opts))]
    (when (and (= :completed status) (outcome/failure-value? receipt))
      (fail! definition results (messages/t :chain/completion-publication-failed) opts))
    (or (:execution/emission-failure last-result)
        (when (outcome/failure-value? receipt) receipt)
        result)))

(comment
  (options {}))
