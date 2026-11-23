;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-observation
  "Serialize ticks, substantive observations and closure for one activation."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.heartbeat-publication :as publication]
            [ai.miniforge.event-stream.heartbeat-clock :as clock]
            [ai.miniforge.event-stream.heartbeat-progress :as progress]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} fence! [state]
  (swap! state assoc :closed? true)
  nil)

(defn ^{:stratum 0} await-idle! [state]
  (locking state nil))

(defn- ^{:stratum 0} observation [model source position]
  (merge (:identity model)
         {:progress/source source
          :progress/sequence position
          :progress/observed-at (clock/wall-time)
          :progress/monotonic-ms (clock/monotonic-ms)}))

(defn- ^{:stratum 0} emit! [stream state interval-ms]
  (let [model (:progress (swap! state update :progress progress/tick))
        payload (progress/payload model (clock/wall-time) (clock/monotonic-ms) interval-ms)
        receipt (publication/publish! stream payload)]
    (when (anomaly/any-anomaly? receipt) (swap! state update :dropped inc))
    receipt))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} tick! [stream state interval-ms]
  (locking state
    (when-not (:closed? @state) (emit! stream state interval-ms))))

(defn ^{:stratum 1} observe! [state source position]
  (locking state
    (when-not (:closed? @state)
      (let [event (observation (:progress @state) source position)]
        (swap! state update :progress progress/observe event))))
  nil)

(comment ::activation-observation-serialization)
