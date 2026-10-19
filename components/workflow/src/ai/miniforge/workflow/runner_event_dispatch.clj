;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-event-dispatch
  "Current durable publication and legacy best-effort transport selection."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.messages :as messages]
            [cheshire.core :as json]
            [org.httpkit.server :as http]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} current-failure [exception]
  (let [failure (response/from-exception exception)]
    (when (instance? InterruptedException exception) (.interrupt (Thread/currentThread)))
    failure))

(defn- ^{:stratum 0} publish-current! [stream event]
  (if (anomaly/any-anomaly? event) event (es/publish! stream event)))

(defn- ^{:stratum 0} nonfatal? [value]
  (not (instance? Error value)))

(defn ^{:stratum 0} websocket-stream?
  [event-stream]
  (boolean (:websocket event-stream)))

(defn ^{:stratum 0} stateful-stream?
  [event-stream]
  (instance? clojure.lang.IDeref event-stream))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publication-failure
  "Current publication failures are values; legacy transport retains warning-only behavior."
  [stream exception warning]
  (if (es/current-event-stream? stream)
    (current-failure exception)
    (println (messages/t warning {:error (ex-message exception)}))))

(defn- ^{:stratum 1} publish-via-websocket!
  "Send event JSON to a WebSocket connection."
  [event-stream event]
  (try+
    (http/send! (:websocket event-stream) (json/generate-string event))
    (catch nonfatal? _
      (println (messages/t :warn/websocket-send {:error (ex-message (:throwable &throw-context))})))))

(defn ^{:stratum 1} dispatchable-event-stream?
  [event-stream]
  (or (websocket-stream? event-stream)
      (stateful-stream? event-stream)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish-event!
  "Publish event to event stream or dashboard WebSocket.
   Current streams return a durable receipt or anomaly, including construction failures.
   Safe to call with nil event-stream (no-op)."
  [event-stream event]
  (try+
    (cond
      (es/current-event-stream? event-stream) (publish-current! event-stream event)
      (websocket-stream? event-stream) (publish-via-websocket! event-stream event)
      (stateful-stream? event-stream) (es/publish! event-stream event)
      :else nil)
    (catch nonfatal? _
      (publication-failure event-stream (:throwable &throw-context) :warn/publish-event))))

(comment
  (dispatchable-event-stream? nil))
