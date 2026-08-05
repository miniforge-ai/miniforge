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
  "HTTP route handlers for views and API endpoints.

   Low-level helpers live in the sibling `handlers.support` namespace and
   the control-intervention chain in `handlers.control`; every public
   `handle-*` endpoint the router dispatches stays here."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.coerce.interface :as coerce]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.web-dashboard.server.responses :as responses]
   [ai.miniforge.web-dashboard.server.filters :as filters]
   [ai.miniforge.web-dashboard.server.handlers.support :as support]
   [ai.miniforge.web-dashboard.server.handlers.control :as control]
   [ai.miniforge.web-dashboard.views :as views]
   [ai.miniforge.web-dashboard.state :as state]
   [ai.miniforge.web-dashboard.filters-new :as filters-new]
   [ai.miniforge.web-dashboard.server.websocket :as ws]))

;------------------------------------------------------------------------------ Layer 0

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

(defn ^{:stratum 0} handle-workflow-detail
  "Workflow detail view."
  [state workflow-id]
  (let [workflow (state/get-workflow-detail state workflow-id)
        wid (try (parse-uuid workflow-id) (catch Exception _ nil))
        events (if wid
                 (state/get-events state {:workflow-id wid :limit support/workflow-detail-event-limit})
                 [])]
    (responses/html-response (views/workflow-detail-view workflow events))))

(defn ^{:stratum 0} handle-api-workflow-events
  "API: Workflow events fragment (for htmx updates)."
  [state workflow-id]
  (let [wid (try (parse-uuid workflow-id) (catch Exception _ nil))]
    (if wid
      (responses/html-response (views/workflow-events-fragment
                                (state/get-events state {:workflow-id wid :limit support/workflow-detail-event-limit})))
      (responses/html-response [:div.empty-state [:p "Invalid workflow ID"]]))))

(defn ^{:stratum 0} handle-api-workflow-panel
  "API: Workflow detail panel fragment (for inline expand)."
  [state workflow-id]
  (let [workflow (state/get-workflow-detail state workflow-id)
        wid (try (parse-uuid workflow-id) (catch Exception _ nil))
        events (if wid
                 (state/get-events state {:workflow-id wid :limit support/workflow-detail-event-limit})
                 [])]
    (responses/html-response (views/workflow-detail-panel workflow events))))

;; API handlers
(defn ^{:stratum 0} handle-api-stats
  "API: Dashboard stats fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        trains (support/filtered-fleet-trains state filter-ast)
        live-workflows (support/filtered-workflow-runs state filter-ast)
        archived-workflows (state/get-archived-workflows state)
        all-workflows (concat live-workflows archived-workflows)]
    (responses/html-response (views/stats-fragment (state/compute-stats trains all-workflows)))))

(defn ^{:stratum 0} handle-api-fleet-grid
  "API: Fleet status grid fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        fleet-state (state/get-fleet-state state)
        filtered-trains (support/filtered-fleet-trains state filter-ast)
        filtered-fleet-state (assoc fleet-state
                                    :trains filtered-trains
                                    :repos (group-by identity
                                                     (mapcat #(map :pr/repo (:train/prs %))
                                                             filtered-trains)))]
    (responses/html-response (views/fleet-grid-fragment filtered-fleet-state))))

(defn ^{:stratum 0} handle-api-trains
  "API: PR Train list fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        filtered-trains (support/filtered-fleet-trains state filter-ast)]
    (responses/html-response (views/train-list-fragment filtered-trains))))

(defn ^{:stratum 0} handle-api-risk
  "API: Risk analysis fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        trains (support/filtered-fleet-trains state filter-ast)]
    (responses/html-response (views/risk-analysis-fragment (state/compute-risk-analysis trains)))))

(defn ^{:stratum 0} handle-api-activity
  "API: Recent activity fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        trains (support/filtered-fleet-trains state filter-ast)]
    (responses/html-response (views/activity-fragment (support/filtered-activity state trains filter-ast)))))

(defn ^{:stratum 0} handle-api-filter-fields
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
                 (support/compute-global-facets state filters-to-show)
                 (filters-new/compute-all-facets (support/pane-data state pane) pane))]
    (responses/html-response (views/filter-modal-fragment
                              {:filters filters-to-show
                               :facets facets
                               :scope scope
                               :pane pane}))))

(defn ^{:stratum 0} handle-api-evidence-detail
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
      (support/anomaly-http-response (support/from-exception e)))))

;; Listener API handlers (N8)
(defn ^{:stratum 0} handle-api-listeners-list
  "API: List active listeners."
  [state]
  (try
    (let [es (:event-stream @state)
          listeners (when es
                      (event-stream/list-listeners es))]
      (responses/json-response {:listeners (or listeners [])}))
    (catch Exception e
      (support/anomaly-http-response (support/from-exception e)))))

(defn ^{:stratum 0} handle-api-listener-register
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
      (support/anomaly-http-response (support/from-exception e)))))

(defn ^{:stratum 0} handle-api-listener-deregister
  "API: Deregister a listener."
  [state listener-id-str]
  (try
    (let [es (:event-stream @state)
          listener-id (parse-uuid listener-id-str)]
      (event-stream/deregister-listener! es listener-id)
      (responses/json-response {:status "deregistered" :listener-id listener-id-str}))
    (catch Exception e
      (support/anomaly-http-response (support/from-exception e)))))

(defn ^{:stratum 0} handle-api-listener-annotate
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
      (support/anomaly-http-response (support/from-exception e)))))

;; Multi-party approval API handlers (N8)
(defn ^{:stratum 0} handle-api-approval-create
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
      (support/anomaly-http-response (support/from-exception e)))))

;; Evidence/Artifact API handlers (N5)
(defn ^{:stratum 0} handle-api-artifact-detail
  "API: Get artifact detail by ID."
  [state artifact-id]
  (try
    (let [artifact-store (:artifact-store @state)
          artifact-id* (support/artifact-id-value artifact-id)
          artifact (when artifact-store
                     (artifact/load-artifact artifact-store artifact-id*))]
      (if artifact
        (responses/json-response {:status "success" :artifact artifact})
        (support/anomaly-http-response
         (support/make-anomaly :anomalies/not-found
                               "Artifact not found or artifact store not configured"
                               {:artifact-id artifact-id}))))
    (catch Exception e
      (support/anomaly-http-response (support/from-exception e)))))

(defn ^{:stratum 0} handle-api-artifact-provenance
  "API: Get provenance chain for an artifact."
  [state artifact-id]
  (try
    (let [artifact-store (:artifact-store @state)
          artifact-id* (support/artifact-id-value artifact-id)
          provenance (when artifact-store
                       (artifact/get-provenance artifact-store artifact-id*))]
      (if provenance
        (responses/json-response {:status "success" :artifact-id artifact-id :provenance provenance})
        (support/anomaly-http-response
         (support/make-anomaly :anomalies/not-found
                               "Provenance not available"
                               {:artifact-id artifact-id}))))
    (catch Exception e
      (support/anomaly-http-response (support/from-exception e)))))

;; Multi-party approval API handlers (N8, continued)
(defn ^{:stratum 0} handle-api-approval-get
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
        (support/anomaly-http-response
         (support/make-anomaly :anomalies/not-found
                               "Approval not found"
                               {:approval-id approval-id-str}))))
    (catch Exception e
      (support/anomaly-http-response (support/from-exception e)))))

(defn ^{:stratum 0} handle-api-approval-sign
  "API: Submit an approval signature.
   POST /api/approvals/:id/sign  body: {:signer str :decision \"approve\"|\"reject\" :reason str}"
  [state approval-id-str body]
  (try
    (let [data (json/parse-string body true)
          approval-id (parse-uuid approval-id-str)
          mgr (:approval-manager @state)
          approval (and mgr (event-stream/get-approval mgr approval-id))]
      (if-not approval
        (support/anomaly-http-response
         (support/make-anomaly :anomalies/not-found
                               "Approval not found"
                               {:approval-id approval-id-str}))
        (let [result (event-stream/submit-approval
                      approval
                      (:signer data)
                      (keyword (:decision data))
                      {:reason (:reason data)})]
          (if (support/response-success? result)
            (do
              (event-stream/update-approval! mgr (:output result))
              (responses/json-response
               {:status "signed"
                :approval-status (name (:approval/status (:output result)))}))
            (support/anomaly-http-response
             (support/make-anomaly :anomalies/incorrect
                                   (get-in result [:error :message] "Signing failed")
                                   {}))))))
    (catch Exception e
      (support/anomaly-http-response (support/from-exception e)))))

;; Workflow control endpoints
(defn ^{:stratum 0} handle-api-workflow-command
  "API: Request a control intervention for a workflow."
  ;; `_state`: the router passes state positionally to every handler, but
  ;; this one only writes a governed intervention request and never reads
  ;; dashboard state.
  [_state workflow-id body]
  (try
    (let [data (json/parse-string body true)
          command (get data :command "unknown")
          result (control/request-workflow-intervention! workflow-id
                                                         command
                                                         (support/command-requester))]
      (if (anomaly/anomaly? result)
        (support/anomaly-http-response result)
        (responses/json-response
         {:status "requested"
          :command command
          :workflow-id workflow-id
          :intervention-id (str (:intervention/id result))})))
    (catch Exception e
      (support/anomaly-http-response
       (support/make-anomaly :anomalies/incorrect
                             (str "Bad request: " (ex-message e))
                             {:workflow-id workflow-id})))))

(defn ^{:stratum 0} handle-structured-control-action
  "Handle a structured control action request (has :action/type)."
  [state workflow-id data]
  (let [action (support/build-control-action data workflow-id)
        requester (:action/requester action)
        auth-result (event-stream/authorize-action
                     event-stream/default-roles action requester)]
    (if (:authorized? auth-result)
      (control/execute-authorized-action state workflow-id action)
      (control/authorization-error-response auth-result (:action/type action)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} handle-api-workflow-command-v2
  "API: Enqueue a structured control action for a workflow.
   Accepts both legacy {:command :pause} and structured {:action/type :pause ...} formats."
  [state workflow-id body]
  (try
    (let [data (json/parse-string body true)]
      (if (:action/type data)
        (handle-structured-control-action state workflow-id data)
        (handle-api-workflow-command state workflow-id body)))
    (catch Exception e
      (support/anomaly-http-response (support/from-exception e)))))
