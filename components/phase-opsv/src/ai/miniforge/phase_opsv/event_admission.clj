;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.event-admission
  "Admit the stream's event profile before assigning retry identity or publishing."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.decision-envelope.interface :as envelope]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.interface.opsv :as opsv]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} DecisionEvent
  [:map [:event/type [:= :gate/decision]]
   [:event/id :uuid] [:event/timestamp inst?] [:workflow/id :uuid]
   [:opsv/evidence-bundle-id :uuid] [:gate/phase [:= :opsv/actuate]]
   [:envelope/id :uuid] [:gate/decision-envelope envelope/DecisionEnvelope]])

(defn- ^{:stratum 0} canonical-failure [failure]
  (if (anomaly/anomaly? failure) failure
      (anomaly/anomaly :unavailable (msg/ts :event/preparation-failed) {})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} current-event [event]
  (let [prepared (events/prepare-current-publication event)]
    (cond
      (anomaly/any-anomaly? prepared) (canonical-failure prepared)
      (m/validate [:or DecisionEvent opsv/DraftProfile] (second prepared)) (second prepared)
      :else (anomaly/anomaly :invalid-input (msg/ts :event/invalid) {}))))

(defn- ^{:stratum 1} legacy-event [event]
  (if-let [explanation (m/explain (get {:gate/decision DecisionEvent} (:event/type event) opsv/OPSVEvent) event)]
    (anomaly/validation-anomaly (msg/ts :event/invalid) :opsv/event event explanation)
    event))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [stream event]
  (cond
    (anomaly/any-anomaly? event) (canonical-failure event)
    (events/current-event-stream? stream) (current-event event)
    :else (legacy-event event)))

(comment
  ::opsv-event-admission)
