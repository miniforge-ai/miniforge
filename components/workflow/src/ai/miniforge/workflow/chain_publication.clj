;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-publication
  "Treat lifecycle publication receipts as part of the triggering operation."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.chain-outcome :as outcome]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} acknowledged [event receipt]
  (cond
    (outcome/failure-value? receipt) receipt
    (and (map? receipt) (not (:rejected? receipt))
         (= (:event/id event) (:event/id receipt))
         (= (:event/type event) (:event/type receipt))
         (= (:event/version event) (:event/version receipt))
         (nat-int? (:event/sequence-number receipt))) receipt
    :else (anomaly/anomaly :unavailable (messages/t :chain/publication-unconfirmed)
                           (select-keys event [:event/id :event/type]))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish! [stream event]
  (if (outcome/failure-value? event) event
      (acknowledged event (events/publish! stream event))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} emit! [opts constructor & args]
  (when-let [stream (:event-stream opts)]
    (publish! stream (apply constructor stream args))))

(defn ^{:stratum 2} emit-step! [opts constructor & args]
  (when-let [stream (:event-stream opts)]
    (let [identity {:workflow/id (:workflow-id opts)}]
      (publish! stream (apply constructor stream (concat args [identity]))))))

(comment
  ::acknowledged-chain-lifecycle)
