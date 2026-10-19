;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.workflow.runner-callbacks
  "Own phase event callbacks and their heartbeat lifetime."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.runner-events :as events]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} phase-name [interceptor]
  (get-in interceptor [:config :phase] (:phase interceptor)))

(defn- ^{:stratum 0} phase-start-options [interceptor]
  (let [config (:config interceptor)
        agent (:agent config)
        expected (if (nil? agent) (:phase/expected-agent config) agent)]
    {:phase/expected-agent expected}))

(defn- ^{:stratum 0} notify-caller [callback & args]
  (when callback (apply callback args)))

(defn- ^{:stratum 0} start-heartbeat! [stream handles context phase opts]
  (when-let [handle (and phase (events/start-phase-heartbeat! stream context phase opts))]
    (swap! handles assoc phase handle)))

(defn- ^{:stratum 0} stop-heartbeat! [handles phase]
  (when-let [handle (get @handles phase)]
    (events/stop-phase-heartbeat! handle)
    (swap! handles dissoc phase)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stop-all-heartbeats! [handles]
  (doseq [phase (keys @handles)]
    (stop-heartbeat! handles phase)))

(defn- ^{:stratum 1} start-phase! [stream handles heartbeat-opts callback context interceptor]
  (let [phase (phase-name interceptor)
        receipt (when phase
                  (events/publish-phase-started! stream context phase (phase-start-options interceptor)))]
    (if (anomaly/any-anomaly? receipt)
      receipt
      (do (start-heartbeat! stream handles context phase heartbeat-opts)
          (notify-caller callback context interceptor)))))

(defn- ^{:stratum 1} complete-phase! [stream handles callback context interceptor result]
  (let [phase (phase-name interceptor)
        _ (stop-heartbeat! handles phase)
        receipt (when phase (events/publish-phase-completed! stream context phase result))]
    (if (anomaly/any-anomaly? receipt)
      receipt
      (notify-caller callback context interceptor result))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} wrap-phase-callbacks
  "Publish before notifying callers; publication anomalies refuse the phase boundary.
   Own heartbeat lifetime independently and stop it even when completion is refused."
  [stream opts]
  (let [handles (atom {})
        heartbeat-opts (cond-> {}
                         (:phase-heartbeat-interval-ms opts)
                         (assoc :interval-ms (:phase-heartbeat-interval-ms opts)))]
    {:on-phase-start (partial start-phase! stream handles heartbeat-opts (:on-phase-start opts))
     :on-phase-complete (partial complete-phase! stream handles (:on-phase-complete opts))
     :stop-phase-heartbeats! (partial stop-all-heartbeats! handles)}))

(comment ::phase-callback-lifetime)
