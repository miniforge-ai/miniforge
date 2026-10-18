;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-publication
  "Treat lifecycle publication receipts as part of the triggering operation."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.chain-event-legacy :as legacy]
            [ai.miniforge.workflow.chain-event-payload :as payload]
            [ai.miniforge.workflow.chain-outcome :as outcome]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} acknowledged [event receipt]
  (let [identity-fields [:event/id :event/type :event/version :workflow/id :scope/type
                         :chain/id :chain/run-id :chain/definition-id :chain/definition-version
                         :edge/id :edge/from-workflow-id :edge/to-workflow-id]]
    (cond
      (outcome/failure-value? receipt) receipt
      (and (map? receipt) (not (:rejected? receipt))
           (= (select-keys event identity-fields) (select-keys receipt identity-fields))
           (nat-int? (:event/sequence-number receipt))
           (or (not (contains? event :event/sequence-number))
               (= (:event/sequence-number event) (:event/sequence-number receipt)))) receipt
      :else (anomaly/anomaly :unavailable (messages/t :chain/publication-unconfirmed)
                             (select-keys event [:event/id :event/type :chain/run-id])))))

(defn- ^{:stratum 0} draft [opts stream type fields]
  (let [record (:chain/run-record opts)]
    (if (contains? opts :chain/event-stream)
      (events/create-chain-event-draft stream type (payload/current record fields))
      (legacy/draft stream type (assoc fields :chain/definition-id (:chain/definition-id opts))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish! [stream event]
  (if (outcome/failure-value? event) event
      (acknowledged event (events/publish! stream event))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} emit! [opts type fields]
  (when-let [stream (or (:chain/event-stream opts) (:event-stream opts))]
    (publish! stream (draft opts stream type fields))))

(defn ^{:stratum 2} emit-step! [opts type step index fields]
  (when-let [stream (or (:chain/event-stream opts) (:event-stream opts))]
    (publish! stream (draft opts stream type (payload/step-fields opts step index fields)))))

(comment
  ::acknowledged-chain-lifecycle)
