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

(ns ai.miniforge.web-dashboard.server
  "HTTP server with WebSocket support for the web dashboard."
  (:require
   [org.httpkit.server :as http]
   [hiccup.page :as page]
   [jsonista.core :as json]))

;------------------------------------------------------------------------------ Layer 0
;; HTML rendering

(defn- render-index-page []
  (page/html5
   [:head
    [:title "Miniforge Dashboard"]
    [:meta {:charset "utf-8"}]
    [:style "body { font-family: 'SF Mono', 'JetBrains Mono', monospace; background: #1e1e1e; color: #d4d4d4; padding: 2rem; margin: 0; }
            h1 { color: #00d4ff; margin: 0 0 1rem 0; }
            .status { margin: 0 0 1rem 0; padding: 1rem; background: #2d2d2d; border-radius: 4px; }
            .connected { color: #4ec9b0; }
            .disconnected { color: #f48771; }
            .event-log { background: #2d2d2d; border-radius: 4px; padding: 1rem; max-height: 600px; overflow-y: auto; }
            .event { border-bottom: 1px solid #3d3d3d; padding: 0.5rem 0; }
            .event:last-child { border-bottom: none; }
            .event-type { color: #00d4ff; font-weight: bold; }
            .event-time { color: #888; font-size: 0.9em; }
            .event-data { color: #d4d4d4; margin-top: 0.25rem; }
            .workflow-id { color: #c586c0; }
            .phase { color: #dcdcaa; }
            .status-success { color: #4ec9b0; }
            .status-error { color: #f48771; }
            .status-running { color: #4fc1ff; }"]]
   [:body
    [:h1 "MINIFORGE | Web Dashboard"]
    [:div.status
     [:p "WebSocket: " [:span#ws-status.disconnected "Connecting..."]]
     [:p [:small [:span#ws-url]]]]
    [:div#content
     [:h2 "Event Stream"]
     [:div#event-log.event-log
      [:p "Waiting for events..."]]]
    [:script {:type "text/javascript"}
     "
     const wsUrl = 'ws://' + window.location.host + '/ws';
     document.getElementById('ws-url').textContent = wsUrl;
     const ws = new WebSocket(wsUrl);
     const status = document.getElementById('ws-status');
     const eventLog = document.getElementById('event-log');
     const events = [];

     ws.onopen = () => {
       status.textContent = 'Connected';
       status.className = 'connected';
       console.log('WebSocket connected');
     };

     ws.onclose = () => {
       status.textContent = 'Disconnected';
       status.className = 'disconnected';
       console.log('WebSocket disconnected');
     };

     ws.onmessage = (event) => {
       const msg = JSON.parse(event.data);
       console.log('Event:', msg);
       events.unshift(msg);
       if (events.length > 100) events.pop();
       renderEvents();
     };

     ws.onerror = (error) => {
       console.error('WebSocket error:', error);
     };

     function renderEvents() {
       if (events.length === 0) {
         eventLog.innerHTML = '<p>Waiting for events...</p>';
         return;
       }

       eventLog.innerHTML = events.map(e => {
         const eventType = e['event/type'] || e.type || 'unknown';
         const timestamp = e['event/timestamp'] || e.timestamp || new Date().toISOString();
         const workflowId = e['workflow-id'] || e['workflow/id'] || '';
         const phase = e.phase || e['workflow/phase'] || '';
         const data = e.data || {};

         let dataStr = '';
         if (data.message) dataStr += data.message;
         if (phase) dataStr += ` [${phase}]`;
         if (workflowId) dataStr += ` <span class=\"workflow-id\">${workflowId.substring(0, 8)}...</span>`;

         return `
           <div class=\"event\">
             <div><span class=\"event-type\">${eventType}</span> <span class=\"event-time\">${new Date(timestamp).toLocaleTimeString()}</span></div>
             ${dataStr ? `<div class=\"event-data\">${dataStr}</div>` : ''}
           </div>
         `;
       }).join('');
     }
     "]]))

;------------------------------------------------------------------------------ Layer 1
;; WebSocket handling

(defn- create-ws-handler [event-stream]
  (let [connections (atom #{})]
    (fn [req]
      (http/as-channel req
        {:on-open
         (fn [ch]
           (println "WebSocket opened")
           (swap! connections conj ch)
           ;; Subscribe to event stream and forward events to this client
           (when event-stream
             (let [subscriber-id (keyword (str "ws-" (hash ch)))]
               (try
                 (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
                   (when es-ns
                     (let [subscribe! (ns-resolve es-ns 'subscribe!)]
                       (subscribe! event-stream subscriber-id
                                   (fn [event]
                                     (try
                                       (http/send! ch (json/write-value-as-string event))
                                       (catch Exception e
                                         (println "Error sending event to WebSocket:" (.getMessage e)))))))))
                 (catch Exception e
                   (println "Error subscribing to event stream:" (.getMessage e)))))))

         :on-close
         (fn [ch _status]
           (println "WebSocket closed")
           (swap! connections disj ch)
           ;; Unsubscribe from event stream
           (when event-stream
             (let [subscriber-id (keyword (str "ws-" (hash ch)))]
               (try
                 (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
                   (when es-ns
                     (let [unsubscribe! (ns-resolve es-ns 'unsubscribe!)]
                       (unsubscribe! event-stream subscriber-id))))
                 (catch Exception e
                   (println "Error unsubscribing from event stream:" (.getMessage e)))))))

         :on-receive
         (fn [ch data]
           (println "Received:" data)
           (http/send! ch (json/write-value-as-string {:echo data})))}))))

;------------------------------------------------------------------------------ Layer 2
;; HTTP routes

(defn- create-handler [event-stream]
  (let [ws-handler (create-ws-handler event-stream)]
    (fn [req]
      (case (:uri req)
        "/" {:status 200
             :headers {"Content-Type" "text/html"}
             :body (render-index-page)}

        "/ws" (ws-handler req)

        "/health" {:status 200
                   :headers {"Content-Type" "application/json"}
                   :body (json/write-value-as-string {:status "ok"})}

        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "Not Found"}))))

;------------------------------------------------------------------------------ Layer 3
;; Server lifecycle

(defn start-server!
  "Start HTTP server with WebSocket support."
  [{:keys [port event-stream] :or {port 8080}}]
  (let [handler (create-handler event-stream)
        server (http/run-server handler {:port port})
        actual-port (if (zero? port)
                      (.getLocalPort (:server-socket @server))
                      port)]
    (println (str "Web dashboard started on http://localhost:" actual-port))
    {:server server
     :port actual-port
     :event-stream event-stream}))

(defn stop-server!
  "Stop HTTP server."
  [{:keys [server]}]
  (when server
    (server :timeout 100)
    (println "Web dashboard stopped")))

(defn get-server-port
  "Get server port."
  [{:keys [port]}]
  port)
