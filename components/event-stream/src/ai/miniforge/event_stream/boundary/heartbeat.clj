;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.heartbeat
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.critical :as critical]
            [ai.miniforge.event-stream.boundary.current-stream :as stream]
            [ai.miniforge.event-stream.heartbeat :as legacy]
            [ai.miniforge.event-stream.heartbeat-current :as current]
            [ai.miniforge.event-stream.heartbeat-runtime-spec :as spec]
            [ai.miniforge.event-stream.messages :as msg]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid [key] (anomaly/anomaly :invalid-input (msg/system key) {}))

(defn- ^{:stratum 0} valid-handle? [handle]
  (and (m/validate spec/Handle handle)
       (m/validate spec/State @(:heartbeat/state handle))))

(defn- ^{:stratum 0} protect [key operation & args]
  (try+ (apply operation args)
    (catch Object failure
      (when-let [fatal (critical/cause (:throwable &throw-context))]
        (critical/propagate! fatal))
      (if (anomaly/any-anomaly? failure) failure
          (anomaly/anomaly :fault (msg/system key) {})))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} start-current! [event-stream workflow-id phase opts]
  (cond
    (not (m/validate spec/StartArguments [workflow-id phase opts])) (invalid :heartbeat/invalid-options)
    (get-in @event-stream [:publication :closed?])
    (anomaly/anomaly :unavailable (msg/system :heartbeat/closed-stream) {})
    :else (current/start! event-stream workflow-id phase
                         (merge {:interval-ms legacy/default-interval-ms :progress-sources :unknown} opts))))

(defn- ^{:stratum 1} stop-current! [handle opts]
  (if (and (valid-handle? handle) (m/validate spec/StopOptions opts))
    (current/stop! handle opts)
    (invalid :heartbeat/invalid-handle)))

(defn ^{:stratum 1} observe-heartbeat-progress! [handle source position]
  (if (and (valid-handle? handle) (m/validate spec/Observation [source position]))
    (protect :heartbeat/invalid-handle current/observe! handle source position)
    (invalid :heartbeat/invalid-handle)))

(defn ^{:stratum 1} heartbeat-dropped-count [handle]
  (if (valid-handle? handle)
    (protect :heartbeat/invalid-handle current/dropped-count handle)
    (invalid :heartbeat/invalid-handle)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start-heartbeat!
  ([event-stream workflow-id phase] (start-heartbeat! event-stream workflow-id phase {}))
  ([event-stream workflow-id phase opts]
   (cond
     (stream/current? event-stream)
     (protect :heartbeat/start-failed start-current! event-stream workflow-id phase opts)
     (stream/legacy? event-stream) (legacy/start-heartbeat! event-stream workflow-id phase opts)
     :else (invalid :heartbeat/invalid-options))))

(defn ^{:stratum 2} stop-heartbeat!
  ([handle] (stop-heartbeat! handle {}))
  ([handle opts]
   (cond
     (nil? handle) nil
     (:heartbeat/current? handle) (protect :heartbeat/invalid-handle stop-current! handle opts)
     :else (legacy/stop-heartbeat! handle opts))))

(comment ::validated-heartbeat-lifecycle)
