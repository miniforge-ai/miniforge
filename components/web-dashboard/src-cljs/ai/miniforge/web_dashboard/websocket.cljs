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

(ns ai.miniforge.web-dashboard.websocket
  "WebSocket client for event stream integration."
  (:require
   [re-frame.core :as rf]
   [ai.miniforge.web-dashboard.events :as events]))

(defonce ws-connection (atom nil))

(declare connect!)

(defn- websocket-url
  "Build WebSocket URL from current location."
  []
  (let [protocol (if (= (.-protocol js/location) "https:") "wss:" "ws:")
        host (.-host js/location)]
    (str protocol "//" host "/ws")))

(defn- handle-message
  "Handle incoming WebSocket message."
  [event]
  (let [data (.-data event)
        parsed (.parse js/JSON data)
        clj-data (js->clj parsed :keywordize-keys true)]
    ;; Dispatch to re-frame
    (rf/dispatch [::events/event-received clj-data])
    ;; Also dispatch workflow-specific events if it's a workflow event
    (when (or (:workflow-id clj-data) (:workflow/id clj-data))
      (rf/dispatch [::events/workflow-event clj-data]))))

(defn- handle-open
  "Handle WebSocket connection opened."
  [_event]
  (js/console.log "WebSocket connected")
  (rf/dispatch [::events/websocket-connected (websocket-url)]))

(defn- handle-close
  "Handle WebSocket connection closed."
  [_event]
  (js/console.log "WebSocket disconnected")
  (rf/dispatch [::events/websocket-disconnected])
  ;; Auto-reconnect after 5 seconds
  (js/setTimeout connect! 5000))

(defn- handle-error
  "Handle WebSocket error."
  [event]
  (js/console.error "WebSocket error:" event)
  (rf/dispatch [::events/websocket-error (str event)]))

(defn connect!
  "Connect to WebSocket server."
  []
  (when-let [existing @ws-connection]
    (.close existing))
  (let [url (websocket-url)
        ws (js/WebSocket. url)]
    (set! (.-onopen ws) handle-open)
    (set! (.-onclose ws) handle-close)
    (set! (.-onerror ws) handle-error)
    (set! (.-onmessage ws) handle-message)
    (reset! ws-connection ws)
    ws))

(defn disconnect!
  "Disconnect from WebSocket server."
  []
  (when-let [ws @ws-connection]
    (.close ws)
    (reset! ws-connection nil)))

(defn send!
  "Send message to WebSocket server."
  [data]
  (when-let [ws @ws-connection]
    (when (= (.-readyState ws) js/WebSocket.OPEN)
      (.send ws (.stringify js/JSON (clj->js data))))))
