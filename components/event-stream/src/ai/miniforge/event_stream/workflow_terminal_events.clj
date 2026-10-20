;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.event-stream.workflow-terminal-events
  "Terminal constructors select legacy envelopes or current workflow drafts."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.core :as legacy]
            [ai.miniforge.event-stream.current-publication :as current]
            [ai.miniforge.event-stream.messages :as msg]
            [ai.miniforge.event-stream.workflow-envelope :as envelope]
            [ai.miniforge.event-stream.workflow-terminal-fields :as fields]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} terminal-draft [stream workflow-id event-type payload]
  (let [event (envelope/create stream event-type workflow-id (:message payload) {})]
    (fields/attach event payload)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} current-completed [stream workflow-id status duration-ms opts]
  (if (= :completed status)
    (terminal-draft stream workflow-id :workflow/completed
                    (dissoc (fields/completed status duration-ms opts) :workflow/status))
    (anomaly/anomaly :invalid-input (msg/system :workflow/invalid-completion-status) {})))

(defn ^{:stratum 1} workflow-failed [stream workflow-id error & [opts]]
  (if (current/active? stream)
    (terminal-draft stream workflow-id :workflow/failed
                    (merge (fields/failed error (:failure/class opts))
                           (select-keys opts [:workflow/failure-phase])))
    (legacy/workflow-failed stream workflow-id error opts)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} workflow-completed [stream workflow-id status & [duration-ms opts]]
  (if (current/active? stream)
    (current-completed stream workflow-id status duration-ms opts)
    (legacy/workflow-completed stream workflow-id status duration-ms opts)))

(comment ::workflow-terminal-facts)
