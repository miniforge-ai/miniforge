;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-event-dispatch
  "Legacy transport selection and best-effort event delivery."
  (:require [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.workflow.messages :as messages]
            [cheshire.core :as json]
            [org.httpkit.server :as http]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} nonfatal? [value]
  (not (instance? Error value)))

(defn ^{:stratum 0} websocket-stream?
  [event-stream]
  (boolean (:websocket event-stream)))

(defn ^{:stratum 0} stateful-stream?
  [event-stream]
  (instance? clojure.lang.IDeref event-stream))

;------------------------------------------------------------------------------ Layer 1

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
   Safe to call with nil event-stream (no-op)."
  [event-stream event]
  (try+
    (cond
      (websocket-stream? event-stream) (publish-via-websocket! event-stream event)
      (stateful-stream? event-stream) (es/publish! event-stream event)
      :else nil)
    (catch nonfatal? _
      (println (messages/t :warn/publish-event {:error (ex-message (:throwable &throw-context))})))))

(comment
  (dispatchable-event-stream? nil))
