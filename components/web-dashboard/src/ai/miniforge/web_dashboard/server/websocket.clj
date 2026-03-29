;; Copyright 2025 miniforge.ai
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.web-dashboard.server.websocket
  "WebSocket handling and real-time event streaming."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [org.httpkit.server :as http]
   [ai.miniforge.web-dashboard.state :as state]))

;------------------------------------------------------------------------------ Layer 0
;; Event normalization and envelopes

(defn ws-event-envelope
  "Build a browser-friendly event wrapper while preserving the raw event payload."
  [event]
  {:type "event"
   :event-type (some-> (:event/type event) str (str/replace #"^:" ""))
   :workflow-id (some-> (:workflow/id event) str)
   :data event
   :event event})

(defn maybe-uuid
  "Parse UUID strings when possible, preserving non-UUID identifiers."
  [value]
  (cond
    (instance? java.util.UUID value) value
    (string? value) (try
                      (parse-uuid value)
                      (catch Exception _
                        value))
    :else value))

(defn maybe-keyword
  "Normalize string-ish values to keywords."
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    (symbol? value) (keyword (name value))
    :else value))

(defn normalize-workflow-event
  "Normalize workflow events arriving over JSON transport."
  [event]
  (let [workflow-id (or (:workflow/id event) (:workflow-id event))
        timestamp (or (:event/timestamp event) (:timestamp event))
        phase (or (:workflow/phase event) (:phase event))
        status (or (:workflow/status event) (:status event))]
    (cond-> event
      (:event/type event)
      (update :event/type maybe-keyword)

      workflow-id
      (assoc :workflow/id (maybe-uuid workflow-id))

      timestamp
      (assoc :event/timestamp timestamp)

      phase
      (assoc :workflow/phase (maybe-keyword phase))

      status
      (assoc :workflow/status (maybe-keyword status))

      (or (:workflow/spec event) (:workflow-spec event))
      (assoc :workflow/spec (or (:workflow/spec event) (:workflow-spec event)))

      true
      (dissoc :workflow-id :timestamp :phase :status :workflow-spec))))

;------------------------------------------------------------------------------ Layer 1
;; Subscribe/unsubscribe

(defn subscribe-client!
  "Subscribe WebSocket client to event stream."
  [event-stream ch]
  (when event-stream
    (let [subscriber-id (keyword (str "ws-" (hash ch)))]
      (try
        (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
          (when es-ns
            (let [subscribe! (ns-resolve es-ns 'subscribe!)]
              (subscribe! event-stream subscriber-id
                          (fn [event]
                            (try
                              (http/send! ch (json/generate-string
                                              (ws-event-envelope event)))
                              (catch Exception e
                                (println "Error sending event to WebSocket:" (ex-message e)))))))))
        (catch Exception e
          (println "Error subscribing to event stream:" (ex-message e)))))))

(defn unsubscribe-client!
  "Unsubscribe WebSocket client from event stream."
  [event-stream ch]
  (when event-stream
    (let [subscriber-id (keyword (str "ws-" (hash ch)))]
      (try
        (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
          (when es-ns
            (let [unsubscribe! (ns-resolve es-ns 'unsubscribe!)]
              (unsubscribe! event-stream subscriber-id))))
        (catch Exception e
          (println "Error unsubscribing from event stream:" (ex-message e)))))))

;------------------------------------------------------------------------------ Layer 2
;; Message handling

(defn handle-ws-message
  "Handle incoming WebSocket message from dashboard UI."
  [state workflow-connections ch data]
  (try
    (let [msg (json/parse-string data true)]
      (cond
        ;; UI refresh request
        (= :refresh (maybe-keyword (:action msg)))
        (http/send! ch (json/generate-string
                       {:type "state"
                        :data (state/get-dashboard-state state)}))

        ;; Control plane command - forward to workflows
        (:command msg)
        (do
          (println "Broadcasting command to workflows:" (:command msg))
          (doseq [workflow-ch @workflow-connections]
            (try
              (http/send! workflow-ch (json/generate-string msg))
              (catch Exception e
                (println "Error sending command to workflow:" (ex-message e))))))

        ;; Unknown message
        :else
        (http/send! ch (json/generate-string {:type "error"
                                              :error "Unknown action"}))))
    (catch Exception e
      (println "Error handling WebSocket message:" (ex-message e)))))

(defn handle-workflow-event
  "Handle incoming event from workflow process.
  Publishes event to dashboard's event stream."
  [state data]
  (try
    (let [event (json/parse-string data true)
          normalized-event (normalize-workflow-event event)]
      ;; Publish event to dashboard's event stream
      (when-let [es (:event-stream @state)]
        (try
          (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
            (when es-ns
              (when-let [publish! (ns-resolve es-ns 'publish!)]
                (publish! es normalized-event))))
          (catch Exception e
            (println "Error publishing workflow event:" (ex-message e))))))
    (catch Exception e
      (println "Error parsing workflow event:" (ex-message e)))))

;------------------------------------------------------------------------------ Layer 3
;; Handler constructors

(defn create-ws-handler
  "Create WebSocket handler with event streaming and workflow control."
  [state workflow-connections]
  (let [connections (atom #{})]
    (fn [req]
      (http/as-channel req
                       {:on-open
                        (fn [ch]
                          (println "WebSocket opened")
                          (swap! connections conj ch)
                          (subscribe-client! (:event-stream @state) ch)
                          ;; Lightweight ack — page already has SSR'd data
                          (http/send! ch (json/generate-string
                                          {:type "init" :status "connected"})))

                        :on-close
                        (fn [ch _status]
                          (println "WebSocket closed")
                          (swap! connections disj ch)
                          (unsubscribe-client! (:event-stream @state) ch))

                        :on-receive
                        (fn [ch data]
                          (handle-ws-message state workflow-connections ch data))}))))

(defn create-events-ws-handler
  "WebSocket handler for workflow processes to publish events."
  [state workflow-connections]
  (fn [req]
    (http/as-channel req
                     {:on-open
                      (fn [ch]
                        (println "Workflow event stream connected")
                        (swap! workflow-connections conj ch))

                      :on-close
                      (fn [ch _status]
                        (println "Workflow event stream disconnected")
                        (swap! workflow-connections disj ch))

                      :on-receive
                      (fn [_ch data]
                        (handle-workflow-event state data))})))
