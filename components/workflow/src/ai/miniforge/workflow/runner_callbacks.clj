;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.workflow.runner-callbacks
  "Own phase event callbacks and their heartbeat lifetime."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.runner-heartbeats :as heartbeats]
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

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} start-phase! [stream handles heartbeat-opts callback context interceptor]
  (let [phase (phase-name interceptor)
        receipt (when phase
                  (events/publish-phase-started! stream context phase (phase-start-options interceptor)))
        heartbeat (when-not (anomaly/any-anomaly? receipt)
                    (heartbeats/start! stream handles context phase heartbeat-opts))]
    (cond
      (anomaly/any-anomaly? receipt) receipt
      (anomaly/any-anomaly? heartbeat) heartbeat
      :else (notify-caller callback context interceptor))))

(defn- ^{:stratum 1} complete-phase! [stream handles callback context interceptor result]
  (let [phase (phase-name interceptor)
        stopped (heartbeats/stop! handles phase)
        receipt (when (and phase (not (anomaly/any-anomaly? stopped)))
                  (events/publish-phase-completed! stream context phase result))]
    (cond
      (anomaly/any-anomaly? stopped) stopped
      (anomaly/any-anomaly? receipt) receipt
      :else (notify-caller callback context interceptor result))))

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
     :stop-phase-heartbeats! (partial heartbeats/stop-all! handles)}))

(comment ::phase-callback-lifetime)
