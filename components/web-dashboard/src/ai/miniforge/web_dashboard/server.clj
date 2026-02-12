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
   [clojure.string :as str]
   [clojure.java.io :as io]
   [hiccup2.core :refer [html]]
   [ai.miniforge.web-dashboard.views :as views]
   [ai.miniforge.web-dashboard.state :as state]
   [ai.miniforge.web-dashboard.filters-new :as filters]))

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
                              (http/send! ch (json/generate-string {:type :event :data event}))
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
    (let [event (json/parse-string data true)
          normalized-event (cond-> event
                             (or (:workflow/id event) (:workflow-id event))
                             (assoc :workflow/id (or (:workflow/id event) (:workflow-id event)))

                             (or (:event/timestamp event) (:timestamp event))
                             (assoc :event/timestamp (or (:event/timestamp event) (:timestamp event)))

                             (or (:workflow/phase event) (:phase event))
                             (assoc :workflow/phase (or (:workflow/phase event) (:phase event)))

                             (or (:workflow/status event) (:status event))
                             (assoc :workflow/status (or (:workflow/status event) (:status event)))

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

(defn- param-value
  "Read request parameter by keyword or string key."
  [params key default]
  (or (get params key)
      (when (keyword? key) (get params (name key)))
      (when (string? key) (get params (keyword key)))
      default))

(defn- ->keyword
  "Convert string/keyword value to keyword when possible."
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [trimmed (str/trim v)
                      cleaned (if (str/starts-with? trimmed ":")
                                (subs trimmed 1)
                                trimmed)]
                  (when-not (str/blank? cleaned)
                    (keyword cleaned)))
    :else nil))

(defn- parse-bool
  "Parse boolean-like string values."
  [v]
  (cond
    (boolean? v) v
    (string? v) (case (str/lower-case (str/trim v))
                  "true" true
                  "false" false
                  v)
    :else v))

(defn- decode-url-part
  "Decode a URL query-string key/value."
  [s]
  (java.net.URLDecoder/decode (str s) "UTF-8"))

(defn- query-string->params
  "Parse raw query-string into a string-keyed map."
  [query-string]
  (if (str/blank? query-string)
    {}
    (reduce
     (fn [acc pair]
       (let [[k v] (str/split pair #"=" 2)
             key (decode-url-part k)
             value (decode-url-part (or v ""))]
         (assoc acc key value)))
     {}
     (remove str/blank? (str/split query-string #"&")))))

(defn- normalize-op
  "Normalize operation token to keyword."
  [op]
  (let [token (str/lower-case (str/trim (str op)))]
    (case token
      ":=" :=
      "=" :=
      ":!=" :!=
      "!=" :!=
      ":in" :in
      "in" :in
      ":contains" :contains
      "contains" :contains
      ":text-search" :text-search
      "text-search" :text-search
      ":<" :<
      "<" :<
      ":>" :>
      ">" :>
      ":<=" :<=
      "<=" :<=
      ":>=" :>=
      ">=" :>=
      ":between" :between
      "between" :between
      :=)))

(defn- normalize-ast-op
  "Normalize AST boolean operator."
  [op]
  (let [token (str/lower-case (str/trim (str op)))]
    (case token
      ":and" :and
      "and" :and
      ":or" :or
      "or" :or
      ":not" :not
      "not" :not
      :and)))

(defn- normalize-filter-value
  "Coerce clause value based on filter spec type/value configuration."
  [spec value]
  (let [filter-type (:filter/type spec)
        filter-values (:filter/values spec)]
    (cond
      (= filter-type :bool)
      (parse-bool value)

      (and (= filter-type :enum)
           (vector? filter-values)
           (every? keyword? filter-values)
           (string? value))
      (or (some #(when (= (name %) (str/replace value #"^:" "")) %) filter-values)
          value)

      (and (= filter-type :enum)
           (string? value)
           (str/starts-with? (str/trim value) ":"))
      (->keyword value)

      :else value)))

(defn- normalize-filter-clause
  "Normalize a single JSON clause to evaluator-friendly shape."
  [clause]
  (let [filter-id (->keyword (or (:filter/id clause)
                                 (get clause "filter/id")))
        spec (when filter-id (filters/get-filter-spec-by-id filter-id))
        value (or (:value clause) (get clause "value"))
        op (or (:op clause) (get clause "op"))]
    {:filter/id filter-id
     :op (normalize-op op)
     :value (if spec
              (normalize-filter-value spec value)
              value)}))

(defn- normalize-filter-ast
  "Normalize JSON AST from browser before evaluation."
  [ast]
  (let [clauses (or (:clauses ast) (get ast "clauses") [])]
    {:op (normalize-ast-op (or (:op ast) (get ast "op")))
     :clauses (->> clauses
                   (map normalize-filter-clause)
                   (filter :filter/id)
                   vec)}))

(defn- parse-filter-ast
  "Parse filter AST from request parameters.

   Expects JSON-encoded filter AST in 'filters' parameter."
  [params]
  (try
    (when-let [filters-json (param-value params :filters nil)]
      (normalize-filter-ast
       (if (string? filters-json)
         (json/parse-string filters-json true)
         filters-json)))
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

(defn- maybe-apply-filters
  [items filter-ast pane]
  (if filter-ast
    (filters/apply-filters items filter-ast pane)
    items))

(defn- filtered-fleet-trains
  [state filter-ast]
  (maybe-apply-filters (:trains (state/get-fleet-state state)) filter-ast :fleet))

(defn- filtered-workflow-runs
  [state filter-ast]
  (maybe-apply-filters (state/get-workflows state) filter-ast :workflows))

(defn- train-risk-score
  [train]
  (let [ci-penalty (case (:pr/ci-status train (:ci-status train))
                     :failed 30
                     :running 5
                     :pending 10
                     0)
        dep-penalty (* 3 (count (:pr/depends-on train [])))
        status-penalty (case (:pr/status train (:train/status train))
                         :changes-requested 15
                         :reviewing 5
                         :merging 10
                         0)
        blocking-penalty (* 5 (count (:train/blocking-prs train [])))]
    (min 100 (+ ci-penalty dep-penalty status-penalty blocking-penalty))))

(defn- stats-from
  [trains workflows]
  (let [risk-scores (map train-risk-score trains)]
    {:trains {:total (count trains)
              :active (count (filter #(#{:open :reviewing :merging} (:train/status %)) trains))}
     :prs {:total (reduce + 0 (map #(count (:train/prs %)) trains))
           :ready (reduce + 0 (map #(count (:train/ready-to-merge %)) trains))
           :blocked (reduce + 0 (map #(count (:train/blocking-prs %)) trains))}
     :health {:healthy (count (filter #(< % 20) risk-scores))
              :warning (count (filter #(and (>= % 20) (< % 50)) risk-scores))
              :critical (count (filter #(>= % 50) risk-scores))}
     :workflows {:total (count workflows)
                 :running (count (filter #(= :running (:status %)) workflows))
                 :completed (count (filter #(= :completed (:status %)) workflows))}}))

(defn- risk-analysis-from
  [trains]
  (let [risks (map (fn [train]
                     (let [score (train-risk-score train)]
                       {:train-id (:train/id train)
                        :train-name (:train/name train)
                        :risk-score score
                        :risk-level (cond
                                      (< score 20) :low
                                      (< score 50) :medium
                                      :else :high)
                        :factors (cond-> []
                                   (seq (:train/blocking-prs train))
                                   (conj {:type :blocking-prs
                                          :count (count (:train/blocking-prs train))
                                          :severity :high})

                                   (some #(= :failed (:pr/ci-status %)) (:train/prs train))
                                   (conj {:type :ci-failures
                                          :count (count (filter #(= :failed (:pr/ci-status %)) (:train/prs train)))
                                          :severity :high})

                                   (> (count (:train/prs train)) 5)
                                   (conj {:type :large-train
                                          :count (count (:train/prs train))
                                          :severity :medium}))}))
                   trains)]
    {:risks (sort-by :risk-score > risks)
     :summary {:high (count (filter #(= :high (:risk-level %)) risks))
               :medium (count (filter #(= :medium (:risk-level %)) risks))
               :low (count (filter #(= :low (:risk-level %)) risks))}}))

(defn- filtered-activity
  [state trains filter-ast]
  (let [activities (state/get-recent-activity state)]
    (if filter-ast
      (let [allowed-train-ids (set (keep :train/id trains))]
        (filter #(contains? allowed-train-ids (:train-id %)) activities))
      activities)))

(defn- handle-api-stats
  "API: Dashboard stats fragment (for htmx updates)."
  [state params]
  (let [filter-ast (parse-filter-ast params)
        trains (filtered-fleet-trains state filter-ast)
        workflows (filtered-workflow-runs state filter-ast)]
    (html-response (views/stats-fragment (stats-from trains workflows)))))

(defn- handle-api-fleet-grid
  "API: Fleet status grid fragment (for htmx updates)."
  [state params]
  (let [filter-ast (parse-filter-ast params)
        fleet-state (state/get-fleet-state state)
        filtered-trains (filtered-fleet-trains state filter-ast)
        filtered-fleet-state (assoc fleet-state
                                    :trains filtered-trains
                                    :repos (group-by identity
                                                     (mapcat #(map :pr/repo (:train/prs %))
                                                             filtered-trains)))]
    (html-response (views/fleet-grid-fragment filtered-fleet-state))))

(defn- handle-api-trains
  "API: PR Train list fragment (for htmx updates)."
  [state params]
  (let [filter-ast (parse-filter-ast params)
        filtered-trains (filtered-fleet-trains state filter-ast)]
    (html-response (views/train-list-fragment filtered-trains))))

(defn- handle-api-risk
  "API: Risk analysis fragment (for htmx updates)."
  [state params]
  (let [filter-ast (parse-filter-ast params)
        trains (filtered-fleet-trains state filter-ast)]
    (html-response (views/risk-analysis-fragment (risk-analysis-from trains)))))

(defn- handle-api-activity
  "API: Recent activity fragment (for htmx updates)."
  [state params]
  (let [filter-ast (parse-filter-ast params)
        trains (filtered-fleet-trains state filter-ast)]
    (html-response (views/activity-fragment (filtered-activity state trains filter-ast)))))

(defn- handle-api-workflows
  "API: Workflow list fragment (for htmx updates)."
  [state params]
  (let [filter-ast (parse-filter-ast params)
        workflows (state/get-workflows state)
        filtered-workflows (if filter-ast
                             (filters/apply-filters workflows filter-ast :workflows)
                             workflows)]
    (html-response (views/workflow-list-fragment filtered-workflows))))

(defn- handle-api-evidence-list
  "API: Evidence list fragment (for htmx updates)."
  [state params]
  (let [filter-ast (parse-filter-ast params)
        evidence-items (:trains (state/get-evidence-state state))
        filtered-items (if filter-ast
                         (filters/apply-filters evidence-items filter-ast :evidence)
                         evidence-items)]
    (html-response (views/evidence-list-fragment filtered-items))))

(defn- handle-api-train-action
  "API: Train action handler."
  [state params]
  (let [action (param-value params :action nil)
        train-id (param-value params :train-id nil)]
    (state/train-action! state train-id action)
    (json-response {:success true})))

(defn- pane-data
  "Get pane data used for facet computation/filtering."
  [state pane]
  (case pane
    :task-status (:tasks (state/get-dag-state state))
    :workflows (state/get-workflows state)
    :evidence (:trains (state/get-evidence-state state))
    :fleet (:trains (state/get-fleet-state state))
    []))

(defn- normalize-facet-counts
  "Normalize facet output into a map."
  [facets]
  (cond
    (map? facets) facets
    (sequential? facets) (into {} facets)
    :else {}))

(defn- compute-global-facets
  "Compute facet counts for global filters across all applicable panes."
  [state global-filters]
  (into {}
        (map (fn [spec]
               (let [filter-id (:filter/id spec)
                     per-pane (for [pane (sort (:filter/applicable-to spec))]
                                (normalize-facet-counts
                                 (filters/compute-facets (pane-data state pane) filter-id pane)))
                     merged (apply merge-with + (cons {} per-pane))
                     top-facets (->> merged
                                     (sort-by val >)
                                     (take 40))]
                 [filter-id top-facets]))
             global-filters)))

(defn- handle-api-filter-fields
  "API: Get available filter fields with faceted counts."
  [state params]
  (let [scope (str/lower-case (str (param-value params :scope "local")))
        pane (or (->keyword (param-value params :pane "task-status"))
                 :task-status)
        all-filters (filters/get-filter-specs)
        filters-to-show (if (= scope "global")
                          (filter #(= :global (:filter/scope %)) all-filters)
                          (filter #(and (= :local (:filter/scope %))
                                        (contains? (:filter/applicable-to %) pane))
                                  all-filters))
        facets (if (= scope "global")
                 (compute-global-facets state filters-to-show)
                 (filters/compute-all-facets (pane-data state pane) pane))]
    (html-response (views/filter-modal-fragment
                    {:filters filters-to-show
                     :facets facets
                     :scope scope
                     :pane pane}))))

(defn- handle-api-events
  "API: Query raw events from event stream."
  [state params]
  (let [workflow-id (when-let [wid (param-value params :workflow-id nil)]
                      (try (parse-uuid wid) (catch Exception _ nil)))
        event-type (when-let [et (param-value params :event-type nil)]
                     (keyword et))
        since (param-value params :since nil)
        limit (try (Integer/parseInt (str (param-value params :limit "100")))
                   (catch Exception _ 100))
        events (state/get-events state {:workflow-id workflow-id
                                        :event-type event-type
                                        :since since
                                        :limit (min limit 500)})]
    (json-response events)))

(defn- handle-api-workflow-events
  "API: Query events for a specific workflow."
  [state workflow-id]
  (let [wid (try (parse-uuid workflow-id) (catch Exception _ nil))]
    (if wid
      (json-response (state/get-events state {:workflow-id wid :limit 500}))
      (json-response {:error "Invalid workflow ID"}))))

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
            params (merge (query-string->params (:query-string req))
                          (:params req))]
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
          (handle-api-stats state params)

          (= uri "/api/fleet/grid")
          (handle-api-fleet-grid state params)

          (= uri "/api/trains")
          (handle-api-trains state params)

          (= uri "/api/risk")
          (handle-api-risk state params)

          (= uri "/api/activity")
          (handle-api-activity state params)

          (= uri "/api/workflows")
          (handle-api-workflows state params)

          (= uri "/api/evidence/list")
          (handle-api-evidence-list state params)

          (= uri "/api/train/action")
          (handle-api-train-action state params)

          (= uri "/api/filter-fields")
          (handle-api-filter-fields state params)

          (= uri "/api/events")
          (handle-api-events state params)

          (.startsWith uri "/api/workflow/")
          (let [rest-uri (subs uri 14)
                [wf-id segment] (str/split rest-uri #"/" 2)]
            (if (= segment "events")
              (handle-api-workflow-events state wf-id)
              (not-found-response)))

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
