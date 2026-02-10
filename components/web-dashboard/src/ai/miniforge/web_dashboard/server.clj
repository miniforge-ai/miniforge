;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
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
  "HTTP server with WebSocket support for the production web dashboard."
  (:require
   [org.httpkit.server :as http]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [hiccup2.core :refer [html]]
   [ai.miniforge.web-dashboard.views :as views]
   [ai.miniforge.web-dashboard.state :as state]))

;------------------------------------------------------------------------------ Layer 0
;; Static file serving

(def content-types
  "MIME types for static files."
  {".html" "text/html"
   ".css"  "text/css"
   ".js"   "application/javascript"
   ".json" "application/json"})

(defn- get-content-type
  "Get content type from file extension."
  [path]
  (or (some (fn [[ext type]]
              (when (.endsWith path ext) type))
            content-types)
      "application/octet-stream"))

(defn- serve-static-file
  "Serve a static file from resources/public."
  [path]
  (if-let [resource (io/resource (str "public" path))]
    {:status 200
     :headers {"Content-Type" (get-content-type path)}
     :body (slurp resource)}
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "Not Found"}))

;------------------------------------------------------------------------------ Layer 1
;; WebSocket handling and real-time updates

(defn- subscribe-client!
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
                              (http/send! ch (json/generate-string event))
                              (catch Exception e
                                (println "Error sending event to WebSocket:" (.getMessage e)))))))))
        (catch Exception e
          (println "Error subscribing to event stream:" (.getMessage e)))))))

(defn- unsubscribe-client!
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
          (println "Error unsubscribing from event stream:" (.getMessage e)))))))

(defn- create-ws-handler [state]
  (let [connections (atom #{})]
    (fn [req]
      (http/as-channel req
                       {:on-open
                        (fn [ch]
                          (println "WebSocket opened")
                          (swap! connections conj ch)
                          (subscribe-client! (:event-stream state) ch)
                          ;; Send initial state
                          (http/send! ch (json/generate-string
                                          {:type :init
                                           :data (state/get-dashboard-state state)})))

                        :on-close
                        (fn [ch _status]
                          (println "WebSocket closed")
                          (swap! connections disj ch)
                          (unsubscribe-client! (:event-stream state) ch))

                        :on-receive
                        (fn [ch data]
                          (try
                            (let [msg (json/parse-string data true)]
                              (case (:action msg)
                                :refresh (http/send! ch (json/generate-string
                                                         {:type :state
                                                          :data (state/get-dashboard-state state)}))
                                (http/send! ch (json/generate-string {:error "Unknown action"}))))
                            (catch Exception e
                              (println "Error handling WebSocket message:" (.getMessage e)))))}))))

;------------------------------------------------------------------------------ Layer 2
;; HTTP routes

(defn- html-response
  "Render hiccup to HTML and return as HTTP response."
  [hiccup-data]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (if (string? hiccup-data)
           hiccup-data
           (str (html hiccup-data)))})

(defn- json-response
  "Return JSON response."
  [data]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string data)})

(defn- create-handler [state]
  (let [ws-handler (create-ws-handler state)]
    (fn [req]
      (let [uri (:uri req)
            params (:params req)]
        (cond
          ;; WebSocket
          (= uri "/ws")
          (ws-handler req)

          ;; Health check
          (= uri "/health")
          (json-response {:status "ok"
                          :version "2.0.0"
                          :uptime (state/get-uptime state)})

          ;; Main dashboard view
          (= uri "/")
          (html-response (views/dashboard-view (state/get-dashboard-state state)))

          ;; PR Fleet Management
          (= uri "/fleet")
          (html-response (views/fleet-view (state/get-fleet-state state)))

          ;; PR Train detail
          (.startsWith uri "/train/")
          (let [train-id (subs uri 7)]
            (html-response (views/train-detail-view
                            (state/get-train-detail state train-id))))

          ;; Evidence view
          (= uri "/evidence")
          (html-response (views/evidence-view (state/get-evidence-state state)))

          ;; DAG Kanban view
          (= uri "/dag")
          (html-response (views/dag-kanban-view (state/get-dag-state state)))

          ;; Workflows list view
          (= uri "/workflows")
          (html-response (views/workflows-view (state/get-workflows state)))

          ;; Workflow detail
          (.startsWith uri "/workflow/")
          (let [id (subs uri 10)]
            (html-response (views/workflow-detail-view
                            (state/get-workflow-detail state id))))

          ;; API: Dashboard stats (for htmx updates)
          (= uri "/api/stats")
          (html-response (views/stats-fragment (state/get-stats state)))

          ;; API: Fleet status grid (for htmx updates)
          (= uri "/api/fleet/grid")
          (html-response (views/fleet-grid-fragment (state/get-fleet-state state)))

          ;; API: PR Train list (for htmx updates)
          (= uri "/api/trains")
          (html-response (views/train-list-fragment (state/get-trains state)))

          ;; API: Risk analysis (for htmx updates)
          (= uri "/api/risk")
          (html-response (views/risk-analysis-fragment (state/get-risk-analysis state)))

          ;; API: Recent activity (for htmx updates)
          (= uri "/api/activity")
          (html-response (views/activity-fragment (state/get-recent-activity state)))

          ;; API: Workflow list
          (= uri "/api/workflows")
          (html-response (views/workflow-list-fragment (state/get-workflows state)))

          ;; API: Train action
          (= uri "/api/train/action")
          (let [action (get params :action)
                train-id (get params :train-id)]
            (state/train-action! state train-id action)
            (json-response {:success true}))

          ;; Static files
          (or (.startsWith uri "/css/")
              (.startsWith uri "/js/"))
          (serve-static-file uri)

          ;; 404
          :else
          {:status 404
           :headers {"Content-Type" "text/plain"}
           :body "Not Found"})))))

;------------------------------------------------------------------------------ Layer 3
;; Server lifecycle

(defn start-server!
  "Start HTTP server with WebSocket support.

   Options:
   - :port - Port to listen on (default 8080)
   - :event-stream - Event stream atom for subscribing to workflow events
   - :pr-train-manager - PR train manager instance
   - :repo-dag-manager - Repo DAG manager instance"
  [{:keys [port event-stream pr-train-manager repo-dag-manager]
    :or {port 8080}}]
  (let [state (state/create-state {:event-stream event-stream
                                   :pr-train-manager pr-train-manager
                                   :repo-dag-manager repo-dag-manager
                                   :start-time (System/currentTimeMillis)})
        handler (create-handler state)
        server (http/run-server handler {:port port})
        actual-port (if (zero? port)
                      (.getLocalPort (:server-socket @server))
                      port)]
    (println "┌─────────────────────────────────────────────────────┐")
    (println "│ Miniforge Web Dashboard                             │")
    (println "│ Production-ready fleet control interface            │")
    (println "├─────────────────────────────────────────────────────┤")
    (println  "│ URL: http://localhost:" actual-port (apply str (repeat (- 28 (count (str actual-port))) " ")) "│")
    (println  "│ WebSocket: ws://localhost:" actual-port "/ws" (apply str (repeat (- 21 (count (str actual-port))) " ")) "│")
    (println "└─────────────────────────────────────────────────────┘")
    {:server server
     :port actual-port
     :state state}))

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
