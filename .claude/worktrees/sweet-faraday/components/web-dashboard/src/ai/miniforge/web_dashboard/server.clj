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
   [clojure.string :as str]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [ai.miniforge.web-dashboard.state :as state]
   [ai.miniforge.web-dashboard.views :as views]
   [ai.miniforge.web-dashboard.server.responses :as responses]
   [ai.miniforge.web-dashboard.server.filters :as filters]
   [ai.miniforge.web-dashboard.server.websocket :as websocket]
   [ai.miniforge.web-dashboard.server.handlers :as handlers]
   [ai.miniforge.web-dashboard.server.archive :as archive-handlers]
   [ai.miniforge.web-dashboard.watcher :as watcher]
   [ai.miniforge.web-dashboard.archive :as archive]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; Discovery file

(defn write-discovery-file!
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
      (println "Warning: Could not write discovery file:" (ex-message e)))))

(defn delete-discovery-file!
  "Remove dashboard discovery file on shutdown."
  []
  (try
    (let [discovery-file (str (System/getProperty "user.home") "/.miniforge/dashboard.port")]
      (when (.exists (io/file discovery-file))
        (.delete (io/file discovery-file))))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1
;; Request routing

(defn create-handler
  "Create main HTTP request handler with routing and workflow event integration."
  [state]
  (let [workflow-connections (atom #{})
        ws-handler (websocket/create-ws-handler state workflow-connections)
        events-ws-handler (websocket/create-events-ws-handler state workflow-connections)]
    (fn [req]
      (let [uri (:uri req)
            params (merge (filters/query-string->params (:query-string req))
                          (:params req))]
        (cond
          ;; WebSocket for UI clients
          (= uri "/ws")
          (ws-handler req)

          ;; WebSocket for workflow event ingestion
          (= uri "/ws/events")
          (events-ws-handler req)

          ;; HTTP event ingestion (POST) — GraalVM-safe alternative to /ws/events
          (and (= uri "/api/events/ingest") (= (:request-method req) :post))
          (try
            (let [body (slurp (:body req))]
              (websocket/handle-workflow-event state body)
              {:status 202 :headers {"Content-Type" "application/json"} :body "{\"status\":\"accepted\"}"})
            (catch Exception e
              {:status 400 :headers {"Content-Type" "application/json"}
               :body (json/generate-string {:error (str "Bad request: " (ex-message e))})}))

          ;; Health check
          (= uri "/health")
          (handlers/handle-health state)

          ;; Main views
          (= uri "/")
          (handlers/handle-dashboard state)

          (= uri "/fleet")
          (handlers/handle-fleet state)

          (.startsWith uri "/train/")
          (handlers/handle-train-detail state (subs uri 7))

          (= uri "/evidence")
          (handlers/handle-evidence state)

          (= uri "/dag")
          (handlers/handle-dag state params)

          (= uri "/workflows")
          (handlers/handle-workflows state)

          (.startsWith uri "/workflow/")
          (handlers/handle-workflow-detail state (subs uri 10))

          ;; API endpoints (htmx fragments)
          (= uri "/api/dashboard/workflows")
          (let [live (state/get-workflows state)
                wfs (if (seq live) live (state/get-archived-workflows state))]
            (responses/html-response (views/workflow-summary-fragment (take 5 wfs))))

          (= uri "/api/stats")
          (handlers/handle-api-stats state params)

          (= uri "/api/fleet/grid")
          (handlers/handle-api-fleet-grid state params)

          (= uri "/api/fleet/repos")
          (handlers/handle-api-fleet-repos state)

          (and (= uri "/api/fleet/repos/add")
               (= (:request-method req) :post))
          (handlers/handle-api-fleet-add-repo state params)

          (and (= uri "/api/fleet/repos/discover")
               (= (:request-method req) :post))
          (handlers/handle-api-fleet-discover state params)

          (and (= uri "/api/fleet/prs/sync")
               (= (:request-method req) :post))
          (handlers/handle-api-fleet-sync state)

          (= uri "/api/trains")
          (handlers/handle-api-trains state params)

          (= uri "/api/risk")
          (handlers/handle-api-risk state params)

          (= uri "/api/activity")
          (handlers/handle-api-activity state params)

          (= uri "/api/workflows")
          (handlers/handle-api-workflows state params)

          (= uri "/api/evidence/list")
          (handlers/handle-api-evidence-list state params)

          ;; Evidence detail: /api/evidence/<workflow-id>
          (and (.startsWith uri "/api/evidence/")
               (not= uri "/api/evidence/list"))
          (let [workflow-id (subs uri 15)]
            (handlers/handle-api-evidence-detail state workflow-id))

          ;; Artifact endpoints: /api/artifacts/<id> and /api/artifacts/<id>/provenance
          (.startsWith uri "/api/artifacts/")
          (let [rest-uri (subs uri 16)
                [artifact-id segment] (str/split rest-uri #"/" 2)]
            (case segment
              "provenance" (handlers/handle-api-artifact-provenance state artifact-id)
              (handlers/handle-api-artifact-detail state artifact-id)))

          ;; Listener endpoints
          (and (= uri "/api/listeners") (= (:request-method req) :get))
          (handlers/handle-api-listeners-list state)

          (and (= uri "/api/listeners") (= (:request-method req) :post))
          (handlers/handle-api-listener-register state (slurp (:body req)))

          (and (.startsWith uri "/api/listeners/")
               (not= (:request-method req) :delete)
               (.endsWith uri "/annotate")
               (= (:request-method req) :post))
          (let [;; /api/listeners/<id>/annotate
                rest-uri (subs uri 16)
                listener-id (first (str/split rest-uri #"/" 2))]
            (handlers/handle-api-listener-annotate state listener-id (slurp (:body req))))

          (and (.startsWith uri "/api/listeners/")
               (= (:request-method req) :delete))
          (let [listener-id (subs uri 16)]
            (handlers/handle-api-listener-deregister state listener-id))

          (= uri "/api/train/action")
          (handlers/handle-api-train-action state params)

          (= uri "/api/filter-fields")
          (handlers/handle-api-filter-fields state params)

          (= uri "/api/events")
          (handlers/handle-api-events state params)

          (= uri "/api/archived-workflows")
          (archive-handlers/handle-archived-workflows state params)

          (and (= uri "/api/archive/retention") (= :post (:request-method req)))
          (archive-handlers/handle-retention state (slurp (:body req)))

          (.startsWith uri "/api/archive/")
          (let [[wf-id segment] (str/split (subs uri (count "/api/archive/")) #"/" 2)]
            (case segment
              "events" (archive-handlers/handle-archived-workflow-events state wf-id)
              "delete" (if (= :post (:request-method req))
                         (archive-handlers/handle-delete state wf-id)
                         (responses/not-found-response))
              (responses/not-found-response)))

          ;; Approval API endpoints (N8)
          (and (= uri "/api/approvals") (= (:request-method req) :post))
          (handlers/handle-api-approval-create state (slurp (:body req)))

          (and (.startsWith uri "/api/approvals/")
               (.endsWith uri "/sign")
               (= (:request-method req) :post))
          (let [rest-uri (subs uri 16)
                approval-id (first (str/split rest-uri #"/" 2))]
            (handlers/handle-api-approval-sign state approval-id (slurp (:body req))))

          (and (.startsWith uri "/api/approvals/")
               (= (:request-method req) :get))
          (let [approval-id (subs uri 16)]
            (handlers/handle-api-approval-get state approval-id))

          (.startsWith uri "/api/workflow/")
          (let [rest-uri (subs uri 14)
                [wf-id segment] (str/split rest-uri #"/" 2)]
            (case segment
              "events" (handlers/handle-api-workflow-events state wf-id)
              "panel" (handlers/handle-api-workflow-panel state wf-id)
              "command" (if (= :post (:request-method req))
                          (handlers/handle-api-workflow-command-v2 state wf-id (slurp (:body req)))
                          (responses/not-found-response))
              "commands" (handlers/handle-api-workflow-commands-poll state wf-id)
              (responses/not-found-response)))

          ;; Static files
          (or (.startsWith uri "/css/")
              (.startsWith uri "/js/")
              (.startsWith uri "/img/"))
          (responses/serve-static-file uri)

          ;; 404
          :else
          (responses/not-found-response))))))

;------------------------------------------------------------------------------ Layer 2
;; Server lifecycle

(defn start-server!
  "Start HTTP server with WebSocket support.

   Options:
   - :port - Port to listen on (default 7878)
   - :event-stream - Event stream atom for subscribing to workflow events
   - :pr-train-manager - PR train manager instance
   - :repo-dag-manager - Repo DAG manager instance"
  [{:keys [port event-stream pr-train-manager repo-dag-manager]
    :or {port 7878}}]
  (let [;; Create dashboard-local event stream with NO file sinks to prevent write-back loop.
        ;; The watcher reads from ~/.miniforge/events/ and publishes to this in-memory-only stream.
        dashboard-event-stream (or event-stream (es/create-event-stream {:sinks []}))
        state (state/create-state {:event-stream dashboard-event-stream
                                   :pr-train-manager pr-train-manager
                                   :repo-dag-manager repo-dag-manager
                                   :start-time (System/currentTimeMillis)})
        handler (create-handler state)
        server (http/run-server handler {:port port :legacy-return-value? false})
        actual-port (http/server-port server)
        ;; Start file watcher to tail-follow event files
        events-dir (str (System/getProperty "user.home") "/.miniforge/events")
        watcher-cleanup (watcher/start-watcher!
                         events-dir
                         (fn [event]
                           (es/publish! dashboard-event-stream event)))]
    ;; Launch background archive scan
    (let [archive-state (:archived-workflows @state)
          loading?      (:archive-loading? @state)]
      (future
        (archive/scan-archive!
         events-dir
         (fn [summary] (swap! archive-state assoc (str (:id summary)) summary))
         (fn []
           (reset! loading? false)
           (println "Archive scan complete:" (count @archive-state) "workflows found")))))

    ;; Write discovery file for auto-connect
    (write-discovery-file! actual-port)

    (println "┌─────────────────────────────────────────────────────┐")
    (println "│ Miniforge Web Dashboard                             │")
    (println "│ Production-ready fleet control interface            │")
    (println "├─────────────────────────────────────────────────────┤")
    (println  "│ URL: http://localhost:" actual-port (apply str (repeat (- 28 (count (str actual-port))) " ")) "│")
    (println  "│ WebSocket: ws://localhost:" actual-port "/ws" (apply str (repeat (- 21 (count (str actual-port))) " ")) "│")
    (println  "│ Events: " events-dir (apply str (repeat (max 1 (- 40 (count events-dir))) " ")) "│")
    (println "└─────────────────────────────────────────────────────┘")
    {:server server
     :port actual-port
     :state state
     :watcher-cleanup watcher-cleanup}))

(defn stop-server!
  "Stop HTTP server and watcher."
  [{:keys [server watcher-cleanup]}]
  (when watcher-cleanup
    (watcher-cleanup))
  (when server
    (delete-discovery-file!)
    (http/server-stop! server {:timeout 100})
    (println "Web dashboard stopped")))

(defn get-server-port
  "Get server port."
  [{:keys [port]}]
  port)
