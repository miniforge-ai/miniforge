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
(ns ai.miniforge.web-dashboard.server.handlers
  "HTTP route handlers for views and API endpoints."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.coerce.interface :as coerce]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.web-dashboard.server.responses :as responses]
   [ai.miniforge.web-dashboard.server.filters :as filters]
   [ai.miniforge.web-dashboard.views :as views]
   [ai.miniforge.web-dashboard.state :as state]
   [ai.miniforge.web-dashboard.filters-new :as filters-new]
   [ai.miniforge.web-dashboard.server.websocket :as ws]))

;------------------------------------------------------------------------------ Layer 0

;; Anomaly/response helpers
;;
;; W2 convergence: producer sites in this brick emit canonical
;; `ai.miniforge.anomaly` maps. The brick's HTTP boundary
;; (`anomaly-http-response`) routes through `response/anomaly->http-response`
;; whose status + user-message tables are still keyed by legacy
;; `:anomalies/*` keywords (translate's `dispatch-key` prefers
;; `:anomaly/subtype` for routing — see #1001). To preserve cross-brick
;; HTTP translation behavior during the convergence, callers pass a
;; legacy `:anomalies/*` category which is mapped to its canonical
;; generic `:anomaly/type` AND carried verbatim under `:anomaly/subtype`.
;; Both are removed once `response/translate` is reshaped to dispatch on
;; canonical generic types directly (anomaly-convergence W5).
(def ^{:stratum 0} ^:private legacy-category->canonical-type
  "Map of legacy `:anomalies/*` categories used at this brick's call
   sites to the canonical generic `:anomaly/type`. The eight cognitect-
   standard rows that the convergence runbook maps 1:1 to a generic
   type — only the four this brick actually emits are listed. Removed
   in W5 once call sites pass canonical types directly."
  {:anomalies/incorrect :invalid-input
   :anomalies/not-found :not-found
   :anomalies/forbidden :unauthorized
   :anomalies/fault     :fault})

(def ^{:stratum 0} ^:private exception-fallback-subtype
  "Legacy `:anomalies/*` category carried as `:anomaly/subtype` on
   exception-derived anomalies so the HTTP translate tables (still
   keyed by `:anomalies/*`) return the canonical 500/`internal error`
   status + message for unexpected exceptions caught at boundaries."
  :anomalies/fault)

(defn ^{:stratum 0} response-success?
  "Check if a response builder result represents success."
  [response]
  (response/success? response))

(defn ^{:stratum 0} anomaly-http-response
  "Translate an anomaly map to a Ring HTTP response.
   Body is JSON-encoded for wire transport."
  [anomaly-map]
  (let [raw (response/anomaly->http-response anomaly-map)]
    (update raw :body json/generate-string)))

;; Constants
(def ^{:stratum 0} ^:private workflow-detail-event-limit
  "Maximum number of events to load per workflow detail view or panel."
  200)

(def ^{:stratum 0} default-dashboard-requester
  "Default requester identity when none is provided in the request body."
  {:principal "dashboard" :role :operator})

;; Filter helpers
(defn ^{:stratum 0} maybe-apply-filters
  [items filter-ast pane]
  (if filter-ast
    (filters-new/apply-filters items filter-ast pane)
    items))

(defn ^{:stratum 0} filtered-activity
  [state trains filter-ast]
  (let [activities (state/get-recent-activity state)]
    (if filter-ast
      (let [allowed-train-ids (set (keep :train/id trains))]
        (filter #(contains? allowed-train-ids (:train-id %)) activities))
      activities)))

(defn ^{:stratum 0} pane-data
  "Get pane data used for facet computation/filtering."
  [state pane]
  (case pane
    :task-status (:tasks (state/get-dag-state state))
    :workflows (state/get-workflows state)
    :evidence (let [es (state/get-evidence-state state)]
                 (concat (:trains es) (:workflows es)))
    :fleet (:trains (state/get-fleet-state state))
    []))

(defn ^{:stratum 0} normalize-facet-counts
  "Normalize facet output into a map."
  [facets]
  (cond
    (map? facets) facets
    (sequential? facets) (into {} facets)
    :else {}))

;; Page handlers
(defn ^{:stratum 0} handle-health
  "Health check endpoint."
  [state]
  (responses/json-response {:status "ok"
                            :version "2.0.0"
                            :uptime (state/get-uptime state)}))

(defn ^{:stratum 0} handle-dashboard
  "Main dashboard view."
  [state]
  (responses/html-response (views/dashboard-view (state/get-dashboard-state state))))

(defn ^{:stratum 0} handle-fleet
  "PR Fleet management view."
  [state]
  (responses/html-response (views/fleet-view (state/get-fleet-state state))))

(defn ^{:stratum 0} handle-train-detail
  "PR Train detail view."
  [state train-id]
  (responses/html-response (views/train-detail-view
                            (state/get-train-detail state train-id))))

(defn ^{:stratum 0} handle-evidence
  "Evidence artifacts view."
  [state]
  (let [evidence-state (state/get-evidence-state state)]
    (responses/html-response (views/evidence-view evidence-state))))

(defn ^{:stratum 0} handle-dag
  "DAG Kanban view."
  [state params]
  (let [dag-state (state/get-dag-state state)
        filter-ast (filters/parse-filter-ast params)
        filtered-tasks (if filter-ast
                        (filters-new/apply-filters (:tasks dag-state) filter-ast :task-status)
                        (:tasks dag-state))
        filtered-state (assoc dag-state :tasks filtered-tasks)]
    (responses/html-response (views/dag-kanban-view filtered-state))))

(defn ^{:stratum 0} handle-workflows
  "Workflows list view."
  [state]
  (responses/html-response (views/workflows-view (state/get-workflows state))))

(defn ^{:stratum 0} handle-api-workflows
  "API: Workflow list fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        workflows (state/get-workflows state)
        filtered-workflows (if filter-ast
                             (filters-new/apply-filters workflows filter-ast :workflows)
                             workflows)]
    (responses/html-response (views/workflow-list-fragment filtered-workflows))))

(defn ^{:stratum 0} handle-api-evidence-list
  "API: Evidence list fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        evidence-state (state/get-evidence-state state)
        filtered-trains (if filter-ast
                          (filters-new/apply-filters (:trains evidence-state) filter-ast :evidence)
                          (:trains evidence-state))]
    (responses/html-response (views/evidence-list-fragment
                              {:trains filtered-trains
                               :workflows (:workflows evidence-state)}))))

(defn ^{:stratum 0} handle-api-train-action
  "API: Train action handler."
  [state params]
  (let [action (filters/param-value params :action nil)
        train-id (filters/param-value params :train-id nil)]
    (state/train-action! state train-id action)
    (responses/json-response {:success true})))

(defn ^{:stratum 0} handle-api-fleet-repos
  "API: Get configured fleet repositories."
  [state]
  (responses/json-response {:success true
                            :repos (state/get-configured-repos state)}))

(defn ^{:stratum 0} handle-api-fleet-add-repo
  "API: Add one configured repository (owner/name)."
  [state params]
  (let [repo (filters/param-value params :repo nil)
        result (state/add-configured-repo! state repo)]
    (responses/json-response result)))

(defn ^{:stratum 0} handle-api-fleet-discover
  "API: Discover repositories from provider and add to fleet config."
  [state params]
  (let [owner (filters/param-value params :owner nil)
        result (state/discover-configured-repos! state {:owner owner})]
    (responses/json-response result)))

(defn ^{:stratum 0} handle-api-fleet-sync
  "API: Sync configured repositories and import open PRs into trains."
  [state]
  (responses/json-response (state/sync-configured-repos! state)))

(defn ^{:stratum 0} handle-api-events
  "API: Query events from event stream with pagination metadata.

   Query parameters:
     workflow-id — UUID filter (optional)
     event-type  — keyword filter, e.g. workflow/phase-started (optional)
     since       — ISO-8601 lower-bound timestamp (optional)
     limit       — max results per page, default 100, cap 500
     offset      — number of events to skip for pagination, default 0

   Response envelope:
     {\"events\" [...], \"offset\" N, \"limit\" N, \"count\" N, \"has_more\" bool}"
  [state params]
  (let [workflow-id (when-let [wid (filters/param-value params :workflow-id nil)]
                      (try (parse-uuid wid) (catch Exception _ nil)))
        event-type  (when-let [et (filters/param-value params :event-type nil)]
                      (keyword et))
        since       (filters/param-value params :since nil)
        limit       (min (coerce/safe-parse-int
                          (str (filters/param-value params :limit "100"))
                          100)
                         500)
        offset      (max (coerce/safe-parse-int
                          (str (filters/param-value params :offset "0"))
                          0)
                         0)
        ;; Fetch offset + limit + 1 so we can detect whether more events exist
        all-events  (state/get-events state {:workflow-id workflow-id
                                             :event-type  event-type
                                             :since       since
                                             :limit       (+ offset limit 1)})
        has-more?   (> (count all-events) (+ offset limit))
        page-events (->> all-events (drop offset) (take limit) vec)]
    (responses/json-response
     {"events"   (mapv ws/serialize-for-json page-events)
      "offset"   offset
      "limit"    limit
      "count"    (count page-events)
      "has_more" has-more?})))

(def ^{:stratum 0} control-intervention-by-command
  "The three run controls this console offers, mapped to the
   intervention verbs the operator channel understands. The legacy
   command name `stop` is gone with the `.edn` poller: the vocabulary
   calls the same operation `cancel`. Anything not in this table is
   rejected at the boundary instead of being written and silently
   dropped downstream."
  {"pause" :pause
   "resume" :resume
   "cancel" :cancel})

;; Evidence/Artifact API handlers (N5)
(defn- ^{:stratum 0} artifact-id-value
  "Normalize URI artifact ids for artifact stores that key by UUID."
  [artifact-id]
  (if (string? artifact-id)
    (try
      (parse-uuid artifact-id)
      (catch Exception _
        artifact-id))
    artifact-id))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} canonical-type
  "Resolve a category keyword to its canonical `:anomaly/type`. Passes
   canonical type keywords through unchanged so call sites can adopt
   the canonical name immediately."
  [category-or-type]
  (or (get legacy-category->canonical-type category-or-type)
      category-or-type))

(defn ^{:stratum 1} from-exception
  "Convert an exception to a canonical anomaly map of type `:fault`,
   preserving the exception class + message + ex-data under
   `:anomaly/data`. Nil exception message falls back to the class
   name per the W2 batch-2 precedent in #1003.

   `:anomaly/subtype` is set to `:anomalies/fault` so the HTTP
   translate boundary (legacy-keyword-keyed during convergence) still
   resolves to 500 + the canonical internal-error user message."
  [^Throwable e]
  (assoc (anomaly/exception-anomaly :fault
                                    (or (ex-message e) (.getName (class e)))
                                    e)
         :anomaly/subtype exception-fallback-subtype))

(defn ^{:stratum 1} filtered-fleet-trains
  [state filter-ast]
  (maybe-apply-filters (:trains (state/get-fleet-state state)) filter-ast :fleet))

(defn ^{:stratum 1} filtered-workflow-runs
  [state filter-ast]
  (maybe-apply-filters (state/get-workflows state) filter-ast :workflows))

(defn ^{:stratum 1} compute-global-facets
  "Compute facet counts for global filters across all applicable panes."
  [state global-filters]
  (into {}
        (map (fn [spec]
               (let [filter-id (:filter/id spec)
                     per-pane (for [pane (sort (:filter/applicable-to spec))]
                                (normalize-facet-counts
                                 (filters-new/compute-facets (pane-data state pane) filter-id pane)))
                     merged (apply merge-with + (cons {} per-pane))
                     top-facets (->> merged
                                     (sort-by val >)
                                     (take 40))]
                 [filter-id top-facets]))
             global-filters)))

(defn ^{:stratum 1} handle-workflow-detail
  "Workflow detail view."
  [state workflow-id]
  (let [workflow (state/get-workflow-detail state workflow-id)
        wid (try (parse-uuid workflow-id) (catch Exception _ nil))
        events (if wid
                 (state/get-events state {:workflow-id wid :limit workflow-detail-event-limit})
                 [])]
    (responses/html-response (views/workflow-detail-view workflow events))))

(defn ^{:stratum 1} handle-api-workflow-events
  "API: Workflow events fragment (for htmx updates)."
  [state workflow-id]
  (let [wid (try (parse-uuid workflow-id) (catch Exception _ nil))]
    (if wid
      (responses/html-response (views/workflow-events-fragment
                                (state/get-events state {:workflow-id wid :limit workflow-detail-event-limit})))
      (responses/html-response [:div.empty-state [:p "Invalid workflow ID"]]))))

(defn ^{:stratum 1} handle-api-workflow-panel
  "API: Workflow detail panel fragment (for inline expand)."
  [state workflow-id]
  (let [workflow (state/get-workflow-detail state workflow-id)
        wid (try (parse-uuid workflow-id) (catch Exception _ nil))
        events (if wid
                 (state/get-events state {:workflow-id wid :limit workflow-detail-event-limit})
                 [])]
    (responses/html-response (views/workflow-detail-panel workflow events))))

(defn- ^{:stratum 1} command-requester
  "Principal recorded on the intervention. Derived SERVER-SIDE ONLY.

   This endpoint has no authenticated identity yet (see miniforge#1460),
   so a body-supplied `:action/requester` is UNTRUSTED and ignored:
   honouring it would let any caller stamp an arbitrary
   `:intervention/requested-by` and poison the audit trail
   (`{\"command\":\"cancel\",\"action\":{\"requester\":{\"principal\":\"…\"}}}`).
   Until an authenticated session identity is threaded here, the surface
   itself is the only honest attribution."
  []
  (:principal default-dashboard-requester))

;; Structured control action handler (N8)
(defn ^{:stratum 1} build-control-action
  "Build a control action map from parsed request data.

   The requester is derived SERVER-SIDE and the body's
   `:action/requester` is ignored — same rule, and same reason, as
   `command-requester` (miniforge#1460). Trusting it here would let a
   caller both spoof the `:control-action/*` audit identity AND name the
   role that `authorize-action` checks, so an unauthenticated request
   could self-authorize. Until an authenticated session identity is
   threaded through, the surface is the only honest requester."
  [data workflow-id]
  (event-stream/create-control-action
   (keyword (:action/type data))
   {:target-type :workflow :target-id workflow-id}
   default-dashboard-requester
   {:justification (:action/justification data)
    :parameters (:action/parameters data)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} make-anomaly
  "Create a canonical anomaly map.

   `category-or-type` accepts either the legacy `:anomalies/*` keyword
   used pre-flip or the canonical `:anomaly/type` keyword directly.
   When a legacy keyword is supplied, the canonical generic type is
   set under `:anomaly/type` AND the legacy keyword is preserved
   verbatim under `:anomaly/subtype` — `response/translate` dispatches
   on subtype first, so the HTTP boundary keeps returning the right
   status + user message during the convergence (translate tables are
   still keyed by `:anomalies/*`). When a canonical type is supplied
   directly the result carries no subtype."
  [category-or-type message & [context]]
  (let [a-type (canonical-type category-or-type)
        ctx (or context {})]
    (if (contains? legacy-category->canonical-type category-or-type)
      (anomaly/sub-anomaly a-type category-or-type message ctx)
      (anomaly/anomaly a-type message ctx))))

;; API handlers
(defn ^{:stratum 2} handle-api-stats
  "API: Dashboard stats fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        trains (filtered-fleet-trains state filter-ast)
        live-workflows (filtered-workflow-runs state filter-ast)
        archived-workflows (state/get-archived-workflows state)
        all-workflows (concat live-workflows archived-workflows)]
    (responses/html-response (views/stats-fragment (state/compute-stats trains all-workflows)))))

(defn ^{:stratum 2} handle-api-fleet-grid
  "API: Fleet status grid fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        fleet-state (state/get-fleet-state state)
        filtered-trains (filtered-fleet-trains state filter-ast)
        filtered-fleet-state (assoc fleet-state
                                    :trains filtered-trains
                                    :repos (group-by identity
                                                     (mapcat #(map :pr/repo (:train/prs %))
                                                             filtered-trains)))]
    (responses/html-response (views/fleet-grid-fragment filtered-fleet-state))))

(defn ^{:stratum 2} handle-api-trains
  "API: PR Train list fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        filtered-trains (filtered-fleet-trains state filter-ast)]
    (responses/html-response (views/train-list-fragment filtered-trains))))

(defn ^{:stratum 2} handle-api-risk
  "API: Risk analysis fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        trains (filtered-fleet-trains state filter-ast)]
    (responses/html-response (views/risk-analysis-fragment (state/compute-risk-analysis trains)))))

(defn ^{:stratum 2} handle-api-activity
  "API: Recent activity fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        trains (filtered-fleet-trains state filter-ast)]
    (responses/html-response (views/activity-fragment (filtered-activity state trains filter-ast)))))

(defn ^{:stratum 2} handle-api-filter-fields
  "API: Get available filter fields with faceted counts."
  [state params]
  (let [scope (str/lower-case (str (filters/param-value params :scope "local")))
        pane (or (filters/->keyword (filters/param-value params :pane "task-status"))
                 :task-status)
        all-filters (filters-new/get-filter-specs)
        filters-to-show (if (= scope "global")
                          (filter #(= :global (:filter/scope %)) all-filters)
                          (filter #(and (= :local (:filter/scope %))
                                        (contains? (:filter/applicable-to %) pane))
                                  all-filters))
        facets (if (= scope "global")
                 (compute-global-facets state filters-to-show)
                 (filters-new/compute-all-facets (pane-data state pane) pane))]
    (responses/html-response (views/filter-modal-fragment
                              {:filters filters-to-show
                               :facets facets
                               :scope scope
                               :pane pane}))))

(defn ^{:stratum 2} handle-api-evidence-detail
  "API: Get evidence bundle detail for a workflow."
  [state workflow-id]
  (try
    (let [wid (try (parse-uuid workflow-id) (catch Exception _ nil))
          evidence-state (state/get-evidence-state state)
          wf-evidence (->> (concat (:trains evidence-state) (:workflows evidence-state))
                           (filter #(or (= (str (:workflow/id %)) workflow-id)
                                        (= (str (:id %)) workflow-id)))
                           first)
          events (when wid (state/get-events state {:workflow-id wid :limit 500}))
          gate-events (filter #(#{:gate/started :gate/passed :gate/failed}
                                (:event/type %)) events)]
      (responses/json-response
       {:status "success"
        :workflow-id workflow-id
        :evidence wf-evidence
        :gate-results gate-events
        :event-count (count events)}))
    (catch Exception e
      (anomaly-http-response (from-exception e)))))

;; Listener API handlers (N8)
(defn ^{:stratum 2} handle-api-listeners-list
  "API: List active listeners."
  [state]
  (try
    (let [es (:event-stream @state)
          listeners (when es
                      (event-stream/list-listeners es))]
      (responses/json-response {:listeners (or listeners [])}))
    (catch Exception e
      (anomaly-http-response (from-exception e)))))

(defn ^{:stratum 2} handle-api-listener-register
  "API: Register a new listener."
  [state body]
  (try
    (let [data (json/parse-string body true)
          es (:event-stream @state)
          listener-spec {:listener/type (keyword (get data :type "watcher"))
                         :listener/capability (keyword (get data :capability "observe"))
                         :listener/identity {:principal (get data :principal "anonymous")}
                         :listener/filters (when-let [f (:filters data)]
                                             {:workflow-ids (mapv parse-uuid (get f :workflow-ids []))
                                              :event-types (mapv keyword (get f :event-types []))})
                         :listener/callback (fn [_event] nil) ; HTTP listeners poll
                         :listener/options (:options data)}
          listener-id (event-stream/register-listener! es listener-spec)]
      (responses/json-response {:listener-id (str listener-id) :status "registered"}))
    (catch Exception e
      (anomaly-http-response (from-exception e)))))

(defn ^{:stratum 2} handle-api-listener-deregister
  "API: Deregister a listener."
  [state listener-id-str]
  (try
    (let [es (:event-stream @state)
          listener-id (parse-uuid listener-id-str)]
      (event-stream/deregister-listener! es listener-id)
      (responses/json-response {:status "deregistered" :listener-id listener-id-str}))
    (catch Exception e
      (anomaly-http-response (from-exception e)))))

(defn ^{:stratum 2} handle-api-listener-annotate
  "API: Submit an annotation from a listener."
  [state listener-id-str body]
  (try
    (let [data (json/parse-string body true)
          es (:event-stream @state)
          listener-id (parse-uuid listener-id-str)
          annotation {:annotation/type (keyword (get data :type "note"))
                      :annotation/content (:content data)
                      :annotation/workflow-id (when-let [wid (:workflow-id data)]
                                                (parse-uuid wid))}]
      (event-stream/submit-annotation! es listener-id annotation)
      (responses/json-response {:status "created"}))
    (catch Exception e
      (anomaly-http-response (from-exception e)))))

;; Multi-party approval API handlers (N8)
(defn ^{:stratum 2} handle-api-approval-create
  "API: Create an approval request.
   POST /api/approvals  body: {:action-id uuid-str :required-signers [str] :quorum int}"
  [state body]
  (try
    (let [data (json/parse-string body true)
          action-id (parse-uuid (str (:action-id data)))
          signers (vec (:required-signers data))
          quorum (get data :quorum (count signers))
          ;; Get or create approval manager from state
          mgr (or (:approval-manager @state)
                  (let [m (event-stream/create-approval-manager)]
                    (swap! state assoc :approval-manager m)
                    m))
          approval (event-stream/create-approval-request
                    action-id signers quorum
                    {:expires-in-hours (get data :expires-in-hours 24)})]
      (event-stream/store-approval! mgr approval)
      (responses/json-response
       {:status "created"
        :approval-id (str (:approval/id approval))
        :expires-at (str (:approval/expires-at approval))}))
    (catch Exception e
      (anomaly-http-response (from-exception e)))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} request-workflow-intervention!
  "Write a `:supervisory/intervention-requested` event into
   `{events-dir}/operator/` for `workflow-id`. The runner's
   operator-event consumer routes it through the intervention
   lifecycle and flips the run's control state; every transition comes
   back on the event stream the console already renders.

   Returns the written event, or an anomaly when the verb is unknown or
   the write fails. Nothing is best-effort here: a control the operator
   pressed either reached the audit stream or reports why not."
  [workflow-id command requested-by]
  (if-let [intervention-type (get control-intervention-by-command
                                  (some-> command name str))]
    (try
      (event-stream/request-intervention!
       {:intervention/type intervention-type
        :intervention/target-type :workflow
        :intervention/target-id (str workflow-id)
        :intervention/requested-by requested-by
        :intervention/request-source :dashboard})
      (catch Exception e
        (make-anomaly :anomalies/fault
                      (str "Intervention request failed: " (ex-message e))
                      {:workflow-id workflow-id :command command})))
    (make-anomaly :anomalies/incorrect
                  (str "Unknown workflow command: " (pr-str command))
                  {:workflow-id workflow-id
                   :supported-commands (vec (sort (keys control-intervention-by-command)))})))

(defn ^{:stratum 3} handle-api-artifact-detail
  "API: Get artifact detail by ID."
  [state artifact-id]
  (try
    (let [artifact-store (:artifact-store @state)
          artifact-id* (artifact-id-value artifact-id)
          artifact (when artifact-store
                     (artifact/load-artifact artifact-store artifact-id*))]
      (if artifact
        (responses/json-response {:status "success" :artifact artifact})
        (anomaly-http-response
         (make-anomaly :anomalies/not-found
                       "Artifact not found or artifact store not configured"
                       {:artifact-id artifact-id}))))
    (catch Exception e
      (anomaly-http-response (from-exception e)))))

(defn ^{:stratum 3} handle-api-artifact-provenance
  "API: Get provenance chain for an artifact."
  [state artifact-id]
  (try
    (let [artifact-store (:artifact-store @state)
          artifact-id* (artifact-id-value artifact-id)
          provenance (when artifact-store
                       (artifact/get-provenance artifact-store artifact-id*))]
      (if provenance
        (responses/json-response {:status "success" :artifact-id artifact-id :provenance provenance})
        (anomaly-http-response
         (make-anomaly :anomalies/not-found
                       "Provenance not available"
                       {:artifact-id artifact-id}))))
    (catch Exception e
      (anomaly-http-response (from-exception e)))))

(defn ^{:stratum 3} authorization-error-response
  "Build an anomaly response for a failed authorization check."
  [auth-result action-type]
  (anomaly-http-response
   (or (:anomaly auth-result)
       (make-anomaly :anomalies/forbidden
                     (:reason auth-result)
                     {:action-type action-type}))))

(defn ^{:stratum 3} handle-api-approval-get
  "API: Get approval status.
   GET /api/approvals/:id"
  [state approval-id-str]
  (try
    (let [approval-id (parse-uuid approval-id-str)
          mgr (:approval-manager @state)]
      (if-let [approval (and mgr (event-stream/get-approval mgr approval-id))]
        (responses/json-response
         {:approval-id (str (:approval/id approval))
          :status (name (event-stream/check-approval-status approval))
          :quorum (:approval/quorum approval)
          :signatures (count (:approval/signatures approval))
          :required-signers (:approval/required-signers approval)
          :expires-at (str (:approval/expires-at approval))})
        (anomaly-http-response
         (make-anomaly :anomalies/not-found
                       "Approval not found"
                       {:approval-id approval-id-str}))))
    (catch Exception e
      (anomaly-http-response (from-exception e)))))

(defn ^{:stratum 3} handle-api-approval-sign
  "API: Submit an approval signature.
   POST /api/approvals/:id/sign  body: {:signer str :decision \"approve\"|\"reject\" :reason str}"
  [state approval-id-str body]
  (try
    (let [data (json/parse-string body true)
          approval-id (parse-uuid approval-id-str)
          mgr (:approval-manager @state)
          approval (and mgr (event-stream/get-approval mgr approval-id))]
      (if-not approval
        (anomaly-http-response
         (make-anomaly :anomalies/not-found
                       "Approval not found"
                       {:approval-id approval-id-str}))
        (let [result (event-stream/submit-approval
                      approval
                      (:signer data)
                      (keyword (:decision data))
                      {:reason (:reason data)})]
          (if (response-success? result)
            (do
              (event-stream/update-approval! mgr (:output result))
              (responses/json-response
               {:status "signed"
                :approval-status (name (:approval/status (:output result)))}))
            (anomaly-http-response
             (make-anomaly :anomalies/incorrect
                           (get-in result [:error :message] "Signing failed")
                           {}))))))
    (catch Exception e
      (anomaly-http-response (from-exception e)))))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} handle-api-workflow-command
  "API: Request a control intervention for a workflow."
  [state workflow-id body]
  (try
    (let [data (json/parse-string body true)
          command (get data :command "unknown")
          result (request-workflow-intervention! workflow-id
                                                 command
                                                 (command-requester))]
      (if (anomaly/anomaly? result)
        (anomaly-http-response result)
        (responses/json-response
         {:status "requested"
          :command command
          :workflow-id workflow-id
          :intervention-id (str (:intervention/id result))})))
    (catch Exception e
      (anomaly-http-response
       (make-anomaly :anomalies/incorrect
                     (str "Bad request: " (ex-message e))
                     {:workflow-id workflow-id})))))

(defn ^{:stratum 4} execute-via-command!
  "Execution function that routes an authorized control action onto the
   governed operator channel. Throws when the request cannot be written
   — `execute-control-action!` records the failure rather than letting
   an unapplied action report success."
  [state workflow-id action]
  (let [cmd (name (:action/type action))
        ;; Same server-side-only rule as command-requester: the action's
        ;; requester came from the request body and is unauthenticated
        ;; (miniforge#1460), so it must not become the governed
        ;; intervention's recorded identity. Attribute to the surface.
        result (request-workflow-intervention!
                workflow-id
                cmd
                (command-requester))]
    (when (anomaly/anomaly? result)
      (throw (ex-info (:anomaly/message result) (:anomaly/data result))))
    {:command cmd
     :workflow-id workflow-id
     :intervention-id (str (:intervention/id result))}))

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} execute-authorized-action
  "Execute a control action that has passed authorization."
  [state workflow-id action]
  (let [es (:event-stream @state)
        result (event-stream/execute-control-action!
                es action (partial execute-via-command! state workflow-id))]
    (responses/json-response {:status "executed" :result result})))

;------------------------------------------------------------------------------ Layer 6

(defn ^{:stratum 6} handle-structured-control-action
  "Handle a structured control action request (has :action/type)."
  [state workflow-id data]
  (let [action (build-control-action data workflow-id)
        requester (:action/requester action)
        auth-result (event-stream/authorize-action
                     event-stream/default-roles action requester)]
    (if (:authorized? auth-result)
      (execute-authorized-action state workflow-id action)
      (authorization-error-response auth-result (:action/type action)))))

;------------------------------------------------------------------------------ Layer 7

(defn ^{:stratum 7} handle-api-workflow-command-v2
  "API: Enqueue a structured control action for a workflow.
   Accepts both legacy {:command :pause} and structured {:action/type :pause ...} formats."
  [state workflow-id body]
  (try
    (let [data (json/parse-string body true)]
      (if (:action/type data)
        (handle-structured-control-action state workflow-id data)
        (handle-api-workflow-command state workflow-id body)))
    (catch Exception e
      (anomaly-http-response (from-exception e)))))
