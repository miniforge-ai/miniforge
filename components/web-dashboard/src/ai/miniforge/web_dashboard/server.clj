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
   [ai.miniforge.web-dashboard.state :as state]
   [ai.miniforge.web-dashboard.filters :as filters]))

;------------------------------------------------------------------------------ Layer 0
;; Static file serving

(def content-types
  "MIME types for static files."
  {".html" "text/html"
   ".css"  "text/css"
   ".js"   "application/javascript"
   ".json" "application/json"
   ".png"  "image/png"
   ".jpg"  "image/jpeg"
   ".jpeg" "image/jpeg"
   ".svg"  "image/svg+xml"
   ".gif"  "image/gif"
   ".ico"  "image/x-icon"})

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
     :body (io/input-stream resource)}
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

(defn- handle-ws-message
  "Handle incoming WebSocket message from dashboard UI.
   Supports refresh requests and control plane commands."
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
                (println "Error sending command to workflow:" (.getMessage e))))))

        ;; Unknown message
        :else
        (http/send! ch (json/generate-string {:error "Unknown action"}))))
    (catch Exception e
      (println "Error handling WebSocket message:" (.getMessage e)))))

(defn- handle-workflow-event
  "Handle incoming event from workflow process.
   Publishes event to dashboard's event stream."
  [state data]
  (try
    (let [event (json/parse-string data true)]
      ;; Publish event to dashboard's event stream
      (when-let [es (:event-stream @state)]
        (try
          (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
            (when es-ns
              (when-let [publish! (ns-resolve es-ns 'publish!)]
                (publish! es event))))
          (catch Exception e
            (println "Error publishing workflow event:" (.getMessage e))))))
    (catch Exception e
      (println "Error parsing workflow event:" (.getMessage e)))))

(defn- create-ws-handler
  "Create WebSocket handler with event streaming and workflow control."
  [state workflow-connections]
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
                          (handle-ws-message state workflow-connections ch data))}))))

(defn- create-events-ws-handler
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

;------------------------------------------------------------------------------ Layer 2
;; HTTP response helpers

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

(defn- not-found-response
  "Return 404 response."
  []
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "Not Found"})

;------------------------------------------------------------------------------ Layer 2.5
;; Filter parsing

(defn- parse-filter-ast
  "Parse filter AST from request parameters.

   Expects JSON-encoded filter AST in 'filters' parameter."
  [params]
  (try
    (when-let [filters-json (get params "filters")]
      (json/parse-string filters-json true))
    (catch Exception e
      (println "Error parsing filter AST:" (.getMessage e))
      nil)))

;------------------------------------------------------------------------------ Layer 3
;; Route handlers

(defn- handle-health
  "Health check endpoint."
  [state]
  (json-response {:status "ok"
                  :version "2.0.0"
                  :uptime (state/get-uptime state)}))

(defn- handle-dashboard
  "Main dashboard view."
  [state]
  (html-response (views/dashboard-view (state/get-dashboard-state state))))

(defn- handle-fleet
  "PR Fleet management view."
  [state]
  (html-response (views/fleet-view (state/get-fleet-state state))))

(defn- handle-train-detail
  "PR Train detail view."
  [state train-id]
  (html-response (views/train-detail-view
                  (state/get-train-detail state train-id))))

(defn- handle-evidence
  "Evidence artifacts view."
  [state]
  (html-response (views/evidence-view (state/get-evidence-state state))))

(defn- handle-dag
  "DAG Kanban view."
  [state params]
  (let [dag-state (state/get-dag-state state)
        filter-ast (parse-filter-ast params)
        filtered-tasks (if filter-ast
                        (filters/apply-filters (:tasks dag-state) filter-ast :task-status)
                        (:tasks dag-state))
        filtered-state (assoc dag-state :tasks filtered-tasks)]
    (html-response (views/dag-kanban-view filtered-state))))

(defn- handle-workflows
  "Workflows list view."
  [state]
  (html-response (views/workflows-view (state/get-workflows state))))

(defn- handle-workflow-detail
  "Workflow detail view."
  [state workflow-id]
  (html-response (views/workflow-detail-view
                  (state/get-workflow-detail state workflow-id))))

(defn- handle-api-stats
  "API: Dashboard stats fragment (for htmx updates)."
  [state]
  (html-response (views/stats-fragment (state/get-stats state))))

(defn- handle-api-fleet-grid
  "API: Fleet status grid fragment (for htmx updates)."
  [state]
  (html-response (views/fleet-grid-fragment (state/get-fleet-state state))))

(defn- handle-api-trains
  "API: PR Train list fragment (for htmx updates)."
  [state]
  (html-response (views/train-list-fragment (state/get-trains state))))

(defn- handle-api-risk
  "API: Risk analysis fragment (for htmx updates)."
  [state]
  (html-response (views/risk-analysis-fragment (state/get-risk-analysis state))))

(defn- handle-api-activity
  "API: Recent activity fragment (for htmx updates)."
  [state]
  (html-response (views/activity-fragment (state/get-recent-activity state))))

(defn- handle-api-workflows
  "API: Workflow list fragment (for htmx updates)."
  [state]
  (html-response (views/workflow-list-fragment (state/get-workflows state))))

(defn- handle-api-train-action
  "API: Train action handler."
  [state params]
  (let [action (get params :action)
        train-id (get params :train-id)]
    (state/train-action! state train-id action)
    (json-response {:success true})))

(defn- handle-api-filter-fields
  "API: Get available filter fields with faceted counts."
  [state params]
  (let [scope (get params "scope" "local")
        pane (keyword (get params "pane" "task-status"))
        ;; Get applicable filters for the pane
        applicable-filters (filters/get-applicable-filters pane)
        filters-to-show (if (= scope "global")
                         (:global applicable-filters)
                         (:local applicable-filters))
        ;; Get data for computing facets
        data (case pane
               :task-status (:tasks (state/get-dag-state state))
               :workflows (state/get-workflows state)
               :evidence (:trains (state/get-evidence-state state))
               :fleet (:trains (state/get-fleet-state state))
               [])
        ;; Compute facets for each filter
        facets (filters/compute-all-facets data pane)]
    (html-response (views/filter-modal-fragment
                    {:filters filters-to-show
                     :facets facets
                     :scope scope
                     :pane pane}))))

(defn- handle-static
  "Static file handler."
  [uri]
  (serve-static-file uri))

;------------------------------------------------------------------------------ Layer 3
;; Request routing

(defn- create-handler
  "Create main HTTP request handler with routing and workflow event integration."
  [state]
  (let [workflow-connections (atom #{})
        ws-handler (create-ws-handler state workflow-connections)
        events-ws-handler (create-events-ws-handler state workflow-connections)]
    (fn [req]
      (let [uri (:uri req)
            params (:params req)]
        (cond
          ;; WebSocket for UI clients
          (= uri "/ws")
          (ws-handler req)

          ;; WebSocket for workflow event ingestion
          (= uri "/ws/events")
          (events-ws-handler req)

          ;; Health check
          (= uri "/health")
          (handle-health state)

          ;; Main views
          (= uri "/")
          (handle-dashboard state)

          (= uri "/fleet")
          (handle-fleet state)

          (.startsWith uri "/train/")
          (handle-train-detail state (subs uri 7))

          (= uri "/evidence")
          (handle-evidence state)

          (= uri "/dag")
          (handle-dag state params)

          (= uri "/workflows")
          (handle-workflows state)

          (.startsWith uri "/workflow/")
          (handle-workflow-detail state (subs uri 10))

          ;; API endpoints (htmx fragments)
          (= uri "/api/stats")
          (handle-api-stats state)

          (= uri "/api/fleet/grid")
          (handle-api-fleet-grid state)

          (= uri "/api/trains")
          (handle-api-trains state)

          (= uri "/api/risk")
          (handle-api-risk state)

          (= uri "/api/activity")
          (handle-api-activity state)

          (= uri "/api/workflows")
          (handle-api-workflows state)

          (= uri "/api/train/action")
          (handle-api-train-action state params)

          (= uri "/api/filter-fields")
          (handle-api-filter-fields state params)

          ;; Static files
          (or (.startsWith uri "/css/")
              (.startsWith uri "/js/")
              (.startsWith uri "/img/"))
          (handle-static uri)

          ;; 404
          :else
          (not-found-response))))))

;------------------------------------------------------------------------------ Layer 4
;; Server lifecycle

(defn- write-discovery-file!
  "Write dashboard discovery file for auto-connect."
  [port]
  (try
    (let [discovery-dir (str (System/getProperty "user.home") "/.miniforge")
          discovery-file (str discovery-dir "/dashboard.port")
          info {:port port
                :pid (.pid (java.lang.ProcessHandle/current))
                :started (str (java.time.Instant/now))}]
      (.mkdirs (io/file discovery-dir))
      (spit discovery-file (json/generate-string info {:pretty true}))
      (println "📡 Workflows will auto-discover dashboard at port" port))
    (catch Exception e
      (println "Warning: Could not write discovery file:" (.getMessage e)))))

(defn- delete-discovery-file!
  "Remove dashboard discovery file on shutdown."
  []
  (try
    (let [discovery-file (str (System/getProperty "user.home") "/.miniforge/dashboard.port")]
      (when (.exists (io/file discovery-file))
        (.delete (io/file discovery-file))))
    (catch Exception _ nil)))

(defn start-server!
  "Start HTTP server with WebSocket support.

   Options:
   - :port - Port to listen on (default 7878)
   - :event-stream - Event stream atom for subscribing to workflow events
   - :pr-train-manager - PR train manager instance
   - :repo-dag-manager - Repo DAG manager instance"
  [{:keys [port event-stream pr-train-manager repo-dag-manager]
    :or {port 7878}}]
  (let [state (state/create-state {:event-stream event-stream
                                   :pr-train-manager pr-train-manager
                                   :repo-dag-manager repo-dag-manager
                                   :start-time (System/currentTimeMillis)})
        handler (create-handler state)
        server (http/run-server handler {:port port})
        actual-port (if (zero? port)
                      (.getLocalPort (:server-socket @server))
                      port)]
    ;; Write discovery file for auto-connect
    (write-discovery-file! actual-port)

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
    (delete-discovery-file!)
    (server :timeout 100)
    (println "Web dashboard stopped")))

(defn get-server-port
  "Get server port."
  [{:keys [port]}]
  port)
