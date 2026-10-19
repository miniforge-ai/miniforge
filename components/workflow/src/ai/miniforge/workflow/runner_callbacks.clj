;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.workflow.runner-callbacks
  "Own phase event callbacks and their heartbeat lifetime."
  (:require [ai.miniforge.workflow.runner-events :as events]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} phase-name [interceptor]
  (get interceptor :phase (get-in interceptor [:config :phase])))

(defn- ^{:stratum 0} stop-heartbeat! [handles phase]
  (when-let [handle (get @handles phase)]
    (events/stop-phase-heartbeat! handle)
    (swap! handles dissoc phase)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stop-all-heartbeats! [handles]
  (doseq [phase (keys @handles)]
    (stop-heartbeat! handles phase)))

(defn- ^{:stratum 1} start-phase! [stream handles heartbeat-opts callback context interceptor]
  (when-let [phase (phase-name interceptor)]
    (events/publish-phase-started! stream context phase)
    (when-let [handle (events/start-phase-heartbeat! stream context phase heartbeat-opts)]
      (swap! handles assoc phase handle)))
  (when callback (callback context interceptor)))

(defn- ^{:stratum 1} complete-phase! [stream handles callback context interceptor result]
  (when-let [phase (phase-name interceptor)]
    (stop-heartbeat! handles phase)
    (events/publish-phase-completed! stream context phase result))
  (when callback (callback context interceptor result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} wrap-phase-callbacks
  "Wrap caller callbacks with event publishing and independently owned heartbeats."
  [stream opts]
  (let [handles (atom {})
        heartbeat-opts (cond-> {}
                         (:phase-heartbeat-interval-ms opts)
                         (assoc :interval-ms (:phase-heartbeat-interval-ms opts)))]
    {:on-phase-start (partial start-phase! stream handles heartbeat-opts (:on-phase-start opts))
     :on-phase-complete (partial complete-phase! stream handles (:on-phase-complete opts))
     :stop-phase-heartbeats! (partial stop-all-heartbeats! handles)}))

(comment ::phase-callback-lifetime)
