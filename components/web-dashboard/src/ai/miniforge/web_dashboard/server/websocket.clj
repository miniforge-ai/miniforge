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
   [org.httpkit.server :as http]
   [cheshire.core :as json]
   [ai.miniforge.web-dashboard.state :as state]))

;------------------------------------------------------------------------------ Layer 0
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
                              (http/send! ch (json/generate-string {:type :event :data event}))
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

;------------------------------------------------------------------------------ Layer 1
;; Message handling

(defn handle-ws-message
  "Handle incoming WebSocket message from dashboard UI."
  [state workflow-connections ch data]
  (try
    (let [msg (json/parse-string data true)]
      (cond
        ;; UI refresh request
        (= :refresh (:action msg))
        (http/send! ch (json/generate-string
                       {:type :state
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
        (http/send! ch (json/generate-string {:error "Unknown action"}))))
    (catch Exception e
      (println "Error handling WebSocket message:" (ex-message e)))))

(defn handle-workflow-event
  "Handle incoming event from workflow process.
   Publishes event to dashboard's event stream."
  [state data]
  (try
    (let [event (json/parse-string data true)
          ;; JSON roundtrip turns keyword values into symbols — re-keywordize critical fields
          event (cond-> event
                  (:event/type event) (update :event/type #(if (keyword? %) % (keyword (str %)))))
          normalized-event (cond-> event
                             (or (:workflow/id event) (:workflow-id event))
                             (assoc :workflow/id (str (or (:workflow/id event) (:workflow-id event))))

                             (or (:event/timestamp event) (:timestamp event))
                             (assoc :event/timestamp (or (:event/timestamp event) (:timestamp event)))

                             (or (:workflow/phase event) (:phase event))
                             (assoc :workflow/phase (let [p (or (:workflow/phase event) (:phase event))]
                                                     (if (keyword? p) p (keyword (str p)))))

                             (or (:workflow/status event) (:status event))
                             (assoc :workflow/status (let [s (or (:workflow/status event) (:status event))]
                                                      (if (keyword? s) s (keyword (str s)))))

                             (or (:workflow/spec event) (:workflow-spec event))
                             (assoc :workflow/spec (or (:workflow/spec event) (:workflow-spec event))))
          normalized-event (dissoc normalized-event :workflow-id :timestamp :phase :status :workflow-spec)]
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

;------------------------------------------------------------------------------ Layer 2
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
                                          {:type :init :status :connected})))

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
