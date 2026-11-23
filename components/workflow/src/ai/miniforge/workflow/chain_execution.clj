;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-execution
  "Chain lifecycle effects composed around normalized child outcomes."
  (:require [ai.miniforge.clock.interface :as clock]
            [ai.miniforge.workflow.chain-step-execution :as child]
            [ai.miniforge.workflow.chain-publication :as publication]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.chain-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} options [opts]
  (if (:workflow-run/correlation-id opts) opts
      (assoc opts :workflow-run/correlation-id (random-uuid))))

(def ^{:stratum 0} step! child/step!)

(defn- ^{:stratum 0} fail! [failed-step error opts]
  (publication/emit! opts :chain/failed {:chain/failed-step failed-step :chain/error error}))

(defn- ^{:stratum 0} terminal-status [last-result]
  (if (or (nil? last-result) (outcome/completed? last-result)) :completed :failed))

(defn- ^{:stratum 0} terminal-error [status last-result receipt]
  (when (outcome/failure-value? receipt)
    (cond-> receipt
      (= :failed status) (outcome/with-child-identity (:execution/id last-result)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} terminal! [result last-result opts]
  (if (= :completed (:chain/status result))
    (publication/emit! opts :chain/completed (select-keys result [:chain/duration-ms :chain/step-count]))
    (fail! (:step/id (peek (:chain/step-results result))) (:execution/error last-result) opts)))

(defn- ^{:stratum 1} publication-failed! [message-key receipt opts]
  (fail! nil (messages/t message-key) opts)
  receipt)

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start! [definition opts]
  (let [step-count (count (:chain/steps definition))
        receipt (publication/emit! opts :chain/started {:chain/step-count step-count})]
    (if (outcome/failure-value? receipt)
      (publication-failed! :chain/start-publication-failed receipt opts)
      receipt)))

(defn ^{:stratum 2} finish! [definition started {:keys [results last-result]} opts]
  (let [duration (clock/elapsed-since started)
        status (terminal-status last-result)
        result (outcome/chain-result definition duration results status)
        receipt (terminal! result last-result opts)]
    (when (and (= :completed status) (outcome/failure-value? receipt))
      (publication-failed! :chain/completion-publication-failed receipt opts))
    (or (:execution/emission-failure last-result)
        (terminal-error status last-result receipt)
        result)))

(comment
  (options {}))
