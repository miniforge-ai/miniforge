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
    [:style "body { font-family: monospace; background: #1e1e1e; color: #d4d4d4; padding: 2rem; }
            h1 { color: #00d4ff; }
            .status { margin: 1rem 0; padding: 1rem; background: #2d2d2d; border-radius: 4px; }
            .connected { color: #4ec9b0; }
            .disconnected { color: #f48771; }"]]
   [:body
    [:h1 "Miniforge Web Dashboard"]
    [:div.status
     [:p "Status: " [:span#ws-status.disconnected "Connecting..."]]
     [:p "WebSocket: " [:span#ws-url]]]
    [:div#content
     [:p "Dashboard UI will display workflow status here."]]
    [:script {:type "text/javascript"}
     "
     const wsUrl = 'ws://' + window.location.host + '/ws';
     document.getElementById('ws-url').textContent = wsUrl;
     const ws = new WebSocket(wsUrl);
     const status = document.getElementById('ws-status');

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
       console.log('WebSocket message:', msg);
       // TODO: Update UI based on event type
     };

     ws.onerror = (error) => {
       console.error('WebSocket error:', error);
     };
     "]]))

;------------------------------------------------------------------------------ Layer 1
;; WebSocket handling

(defn- ws-handler [req]
  (http/as-channel req
    {:on-open (fn [_ch] (println "WebSocket opened"))
     :on-close (fn [_ch _status] (println "WebSocket closed"))
     :on-receive (fn [ch data]
                   (println "Received:" data)
                   ;; Echo back for now
                   (http/send! ch (json/write-value-as-string {:echo data})))}))

;------------------------------------------------------------------------------ Layer 2
;; HTTP routes

(defn- handler [req]
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
     :body "Not Found"}))

;------------------------------------------------------------------------------ Layer 3
;; Server lifecycle

(defn start-server!
  "Start HTTP server with WebSocket support."
  [{:keys [port event-stream] :or {port 8080}}]
  (let [server (http/run-server handler {:port port})
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
