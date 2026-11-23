;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.gate-events
  "Gate evidence payloads over the stream's workflow envelope profile."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.messages :as msg]
            [ai.miniforge.event-stream.workflow-envelope :as envelope]
            [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private rule-option-fields
  {:severity :rule/severity
   :enforcement :rule/enforcement
   :violation :rule/violation
   :envelope-id :envelope/id})

(defn- ^{:stratum 0} evidence-event [stream workflow-id event-type message payload]
  (let [event (envelope/create stream event-type workflow-id message {})]
    (if (anomaly/any-anomaly? event) event (merge payload event))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} rule-payload [phase rule-id status extra]
  (let [selected (select-keys extra (keys rule-option-fields))
        renamed (set/rename-keys selected rule-option-fields)
        present (into {} (filter (comp some? val)) renamed)]
    (merge {:gate/phase phase
            :rule/id rule-id
            :rule/status status}
           present)))

(defn ^{:stratum 1} phase-decision [stream workflow-id phase decision]
  (let [phase-label (if phase (name phase) (msg/t :gate/unknown-phase))
        decision-label (some-> (:envelope/decision decision) name)
        message (msg/t :gate/decision {:phase phase-label :decision decision-label})
        payload {:gate/phase phase
                 :envelope/id (:envelope/id decision)
                 :gate/decision-envelope decision}]
    (evidence-event stream workflow-id :gate/decision message payload)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} gate-rule-applied [stream workflow-id phase rule-id status & [extra]]
  (let [phase-label (name phase)
        status-label (name status)
        message (msg/t :gate/rule-applied {:phase phase-label :rule-id rule-id :status status-label})
        payload (rule-payload phase rule-id status extra)]
    (evidence-event stream workflow-id :gate/rule-applied message payload)))

(comment
  ::gate-evidence-constructors)
