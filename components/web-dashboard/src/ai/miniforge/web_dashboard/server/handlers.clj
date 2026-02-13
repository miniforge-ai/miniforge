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
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [ai.miniforge.web-dashboard.server.responses :as responses]
   [ai.miniforge.web-dashboard.server.filters :as filters]
   [ai.miniforge.web-dashboard.views :as views]
   [ai.miniforge.web-dashboard.state :as state]
   [ai.miniforge.web-dashboard.filters-new :as filters-new]))

;------------------------------------------------------------------------------ Layer 0
;; Filter helpers

(defn- maybe-apply-filters
  [items filter-ast pane]
  (if filter-ast
    (filters-new/apply-filters items filter-ast pane)
    items))

(defn- filtered-fleet-trains
  [state filter-ast]
  (maybe-apply-filters (:trains (state/get-fleet-state state)) filter-ast :fleet))

(defn- filtered-workflow-runs
  [state filter-ast]
  (maybe-apply-filters (state/get-workflows state) filter-ast :workflows))

(defn- filtered-activity
  [state trains filter-ast]
  (let [activities (state/get-recent-activity state)]
    (if filter-ast
      (let [allowed-train-ids (set (keep :train/id trains))]
        (filter #(contains? allowed-train-ids (:train-id %)) activities))
      activities)))

(defn- pane-data
  "Get pane data used for facet computation/filtering."
  [state pane]
  (case pane
    :task-status (:tasks (state/get-dag-state state))
    :workflows (state/get-workflows state)
    :evidence (let [es (state/get-evidence-state state)]
                 (concat (:trains es) (:workflows es)))
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
                                 (filters-new/compute-facets (pane-data state pane) filter-id pane)))
                     merged (apply merge-with + (cons {} per-pane))
                     top-facets (->> merged
                                     (sort-by val >)
                                     (take 40))]
                 [filter-id top-facets]))
             global-filters)))

;------------------------------------------------------------------------------ Layer 1
;; Page handlers

(defn handle-health
  "Health check endpoint."
  [state]
  (responses/json-response {:status "ok"
                            :version "2.0.0"
                            :uptime (state/get-uptime state)}))

(defn handle-dashboard
  "Main dashboard view."
  [state]
  (responses/html-response (views/dashboard-view (state/get-dashboard-state state))))

(defn handle-fleet
  "PR Fleet management view."
  [state]
  (responses/html-response (views/fleet-view (state/get-fleet-state state))))

(defn handle-train-detail
  "PR Train detail view."
  [state train-id]
  (responses/html-response (views/train-detail-view
                            (state/get-train-detail state train-id))))

(defn handle-evidence
  "Evidence artifacts view."
  [state]
  (let [evidence-state (state/get-evidence-state state)]
    (responses/html-response (views/evidence-view evidence-state))))

(defn handle-dag
  "DAG Kanban view."
  [state params]
  (let [dag-state (state/get-dag-state state)
        filter-ast (filters/parse-filter-ast params)
        filtered-tasks (if filter-ast
                        (filters-new/apply-filters (:tasks dag-state) filter-ast :task-status)
                        (:tasks dag-state))
        filtered-state (assoc dag-state :tasks filtered-tasks)]
    (responses/html-response (views/dag-kanban-view filtered-state))))

(defn handle-workflows
  "Workflows list view."
  [state]
  (responses/html-response (views/workflows-view (state/get-workflows state))))

(defn handle-workflow-detail
  "Workflow detail view."
  [state workflow-id]
  (let [workflow (state/get-workflow-detail state workflow-id)
        wid (try (parse-uuid workflow-id) (catch Exception _ nil))
        events (if wid
                 (state/get-events state {:workflow-id wid :limit 50})
                 [])]
    (responses/html-response (views/workflow-detail-view workflow events))))

;------------------------------------------------------------------------------ Layer 2
;; API handlers

(defn handle-api-stats
  "API: Dashboard stats fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        trains (filtered-fleet-trains state filter-ast)
        live-workflows (filtered-workflow-runs state filter-ast)
        archived-workflows (state/get-archived-workflows state)
        all-workflows (concat live-workflows archived-workflows)]
    (responses/html-response (views/stats-fragment (state/compute-stats trains all-workflows)))))

(defn handle-api-fleet-grid
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

(defn handle-api-trains
  "API: PR Train list fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        filtered-trains (filtered-fleet-trains state filter-ast)]
    (responses/html-response (views/train-list-fragment filtered-trains))))

(defn handle-api-risk
  "API: Risk analysis fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        trains (filtered-fleet-trains state filter-ast)]
    (responses/html-response (views/risk-analysis-fragment (state/compute-risk-analysis trains)))))

(defn handle-api-activity
  "API: Recent activity fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        trains (filtered-fleet-trains state filter-ast)]
    (responses/html-response (views/activity-fragment (filtered-activity state trains filter-ast)))))

(defn handle-api-workflows
  "API: Workflow list fragment (for htmx updates)."
  [state params]
  (let [filter-ast (filters/parse-filter-ast params)
        workflows (state/get-workflows state)
        filtered-workflows (if filter-ast
                             (filters-new/apply-filters workflows filter-ast :workflows)
                             workflows)]
    (responses/html-response (views/workflow-list-fragment filtered-workflows))))

(defn handle-api-evidence-list
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

(defn handle-api-train-action
  "API: Train action handler."
  [state params]
  (let [action (filters/param-value params :action nil)
        train-id (filters/param-value params :train-id nil)]
    (state/train-action! state train-id action)
    (responses/json-response {:success true})))

(defn handle-api-fleet-repos
  "API: Get configured fleet repositories."
  [state]
  (responses/json-response {:success true
                            :repos (state/get-configured-repos state)}))

(defn handle-api-fleet-add-repo
  "API: Add one configured repository (owner/name)."
  [state params]
  (let [repo (filters/param-value params :repo nil)
        result (state/add-configured-repo! state repo)]
    (responses/json-response result)))

(defn handle-api-fleet-discover
  "API: Discover repositories from provider and add to fleet config."
  [state params]
  (let [owner (filters/param-value params :owner nil)
        result (state/discover-configured-repos! state {:owner owner})]
    (responses/json-response result)))

(defn handle-api-fleet-sync
  "API: Sync configured repositories and import open PRs into trains."
  [state]
  (responses/json-response (state/sync-configured-repos! state)))

(defn handle-api-filter-fields
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

(defn handle-api-events
  "API: Query raw events from event stream."
  [state params]
  (let [workflow-id (when-let [wid (filters/param-value params :workflow-id nil)]
                      (try (parse-uuid wid) (catch Exception _ nil)))
        event-type (when-let [et (filters/param-value params :event-type nil)]
                     (keyword et))
        since (filters/param-value params :since nil)
        limit (try (Integer/parseInt (str (filters/param-value params :limit "100")))
                   (catch Exception _ 100))
        events (state/get-events state {:workflow-id workflow-id
                                        :event-type event-type
                                        :since since
                                        :limit (min limit 500)})]
    (responses/json-response events)))

(defn handle-api-workflow-events
  "API: Workflow events fragment (for htmx updates)."
  [state workflow-id]
  (let [wid (try (parse-uuid workflow-id) (catch Exception _ nil))]
    (if wid
      (responses/html-response (views/workflow-events-fragment
                                (state/get-events state {:workflow-id wid :limit 50})))
      (responses/html-response [:div.empty-state [:p "Invalid workflow ID"]]))))

(defn handle-api-workflow-panel
  "API: Workflow detail panel fragment (for inline expand)."
  [state workflow-id]
  (let [workflow (state/get-workflow-detail state workflow-id)
        wid (try (parse-uuid workflow-id) (catch Exception _ nil))
        events (if wid
                 (state/get-events state {:workflow-id wid :limit 50})
                 [])]
    (responses/html-response (views/workflow-detail-panel workflow events))))

(defn- write-command-file!
  "Atomic write of a command file to ~/.miniforge/commands/<workflow-id>/<timestamp>.edn.
   Writes to .tmp then renames for atomicity."
  [workflow-id command]
  (try
    (let [commands-dir (io/file (System/getProperty "user.home")
                                ".miniforge" "commands" (str workflow-id))
          timestamp (System/currentTimeMillis)
          target-file (io/file commands-dir (str timestamp ".edn"))
          tmp-file (io/file commands-dir (str timestamp ".edn.tmp"))
          command-data {:command (keyword command) :timestamp timestamp}]
      (.mkdirs commands-dir)
      (spit tmp-file (pr-str command-data))
      (.renameTo tmp-file target-file))
    (catch Exception _
      ;; Best-effort — fall through to in-memory enqueue
      nil)))

(defn handle-api-workflow-command
  "API: Enqueue a control command for a workflow."
  [state workflow-id body]
  (try
    (let [data (json/parse-string body true)
          command (or (:command data) "unknown")]
      ;; Write command file for CLI filesystem polling
      (write-command-file! workflow-id command)
      ;; Keep in-memory enqueue for state tracking
      (state/enqueue-command! state workflow-id command)
      (responses/json-response {:status "queued" :command command :workflow-id workflow-id}))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error (str "Bad request: " (ex-message e))})})))

(defn handle-api-workflow-commands-poll
  "API: Poll and dequeue pending commands for a workflow."
  [state workflow-id]
  (let [commands (state/dequeue-commands! state workflow-id)]
    (responses/json-response {:commands commands})))

;------------------------------------------------------------------------------ Layer 3
;; Evidence/Artifact API handlers (N5)

(defn handle-api-evidence-detail
  "API: Get evidence bundle detail for a workflow."
  [state workflow-id]
  (try
    (let [wid (try (parse-uuid workflow-id) (catch Exception _ nil))
          evidence-state (state/get-evidence-state state)
          ;; Find matching workflow evidence
          wf-evidence (->> (concat (:trains evidence-state) (:workflows evidence-state))
                           (filter #(or (= (str (:workflow/id %)) workflow-id)
                                        (= (str (:id %)) workflow-id)))
                           first)
          ;; Get events for this workflow from event stream
          events (when wid (state/get-events state {:workflow-id wid :limit 500}))
          ;; Extract evidence-related events
          gate-events (filter #(#{:gate/started :gate/passed :gate/failed}
                                (:event/type %)) events)]
      (responses/json-response
       {:workflow-id workflow-id
        :evidence wf-evidence
        :gate-results gate-events
        :event-count (count events)}))
    (catch Exception e
      (responses/json-response {:error (str "Failed to fetch evidence: " (.getMessage e))}))))

(defn handle-api-artifact-detail
  "API: Get artifact detail by ID."
  [_state artifact-id]
  (try
    ;; Query artifact store via requiring-resolve
    (let [artifact (try
                     (when-let [get-fn (requiring-resolve 'ai.miniforge.artifact.interface/get-artifact)]
                       (get-fn artifact-id))
                     (catch Exception _ nil))]
      (if artifact
        (responses/json-response {:artifact artifact})
        (responses/json-response {:artifact-id artifact-id
                                  :status "not-found"
                                  :message "Artifact not found or artifact store not available"})))
    (catch Exception e
      (responses/json-response {:error (str "Failed to fetch artifact: " (.getMessage e))}))))

(defn handle-api-artifact-provenance
  "API: Get provenance chain for an artifact."
  [_state artifact-id]
  (try
    (let [provenance (try
                       (when-let [prov-fn (requiring-resolve 'ai.miniforge.artifact.interface/get-provenance)]
                         (prov-fn artifact-id))
                       (catch Exception _ nil))]
      (if provenance
        (responses/json-response {:artifact-id artifact-id :provenance provenance})
        (responses/json-response {:artifact-id artifact-id
                                  :status "not-found"
                                  :message "Provenance not available"})))
    (catch Exception e
      (responses/json-response {:error (str "Failed to fetch provenance: " (.getMessage e))}))))

;------------------------------------------------------------------------------ Layer 3
;; Listener API handlers (N8)

(defn handle-api-listeners-list
  "API: List active listeners."
  [state]
  (try
    (let [es (:event-stream @state)
          listeners (when es
                      ((requiring-resolve 'ai.miniforge.event-stream.interface/list-listeners) es))]
      (responses/json-response {:listeners (or listeners [])}))
    (catch Exception e
      (responses/json-response {:error (str "Failed to list listeners: " (.getMessage e))}))))

(defn handle-api-listener-register
  "API: Register a new listener."
  [state body]
  (try
    (let [data (json/parse-string body true)
          es (:event-stream @state)
          listener-spec {:listener/type (keyword (or (:type data) "watcher"))
                         :listener/capability (keyword (or (:capability data) "observe"))
                         :listener/identity {:principal (or (:principal data) "anonymous")}
                         :listener/filters (when-let [f (:filters data)]
                                             {:workflow-ids (mapv parse-uuid (or (:workflow-ids f) []))
                                              :event-types (mapv keyword (or (:event-types f) []))})
                         :listener/callback (fn [_event] nil) ; HTTP listeners poll
                         :listener/options (:options data)}
          register-fn (requiring-resolve 'ai.miniforge.event-stream.interface/register-listener!)
          listener-id (register-fn es listener-spec)]
      (responses/json-response {:listener-id (str listener-id) :status "registered"}))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error (str "Failed to register listener: " (.getMessage e))})})))

(defn handle-api-listener-deregister
  "API: Deregister a listener."
  [state listener-id-str]
  (try
    (let [es (:event-stream @state)
          listener-id (parse-uuid listener-id-str)
          deregister-fn (requiring-resolve 'ai.miniforge.event-stream.interface/deregister-listener!)]
      (deregister-fn es listener-id)
      (responses/json-response {:status "deregistered" :listener-id listener-id-str}))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error (str "Failed to deregister: " (.getMessage e))})})))

(defn handle-api-listener-annotate
  "API: Submit an annotation from a listener."
  [state listener-id-str body]
  (try
    (let [data (json/parse-string body true)
          es (:event-stream @state)
          listener-id (parse-uuid listener-id-str)
          annotation {:annotation/type (keyword (or (:type data) "note"))
                      :annotation/content (:content data)
                      :annotation/workflow-id (when-let [wid (:workflow-id data)]
                                                (parse-uuid wid))}
          submit-fn (requiring-resolve 'ai.miniforge.event-stream.interface/submit-annotation!)]
      (submit-fn es listener-id annotation)
      (responses/json-response {:status "created"}))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error (str "Annotation failed: " (.getMessage e))})})))

;------------------------------------------------------------------------------ Layer 3
;; Structured control action handler (N8)

(defn handle-api-workflow-command-v2
  "API: Enqueue a structured control action for a workflow.
   Accepts both legacy {:command :pause} and structured {:action/type :pause ...} formats."
  [state workflow-id body]
  (try
    (let [data (json/parse-string body true)]
      (if (:action/type data)
        ;; New structured control action
        (let [es (:event-stream @state)
              create-fn (requiring-resolve 'ai.miniforge.event-stream.interface/create-control-action)
              auth-fn (requiring-resolve 'ai.miniforge.event-stream.interface/authorize-action)
              exec-fn (requiring-resolve 'ai.miniforge.event-stream.interface/execute-control-action!)
              roles (get (deref (requiring-resolve 'ai.miniforge.event-stream.control/default-roles)) :_not-derefable
                         ((requiring-resolve 'ai.miniforge.event-stream.control/default-roles)))
              action-type (keyword (:action/type data))
              requester (or (:action/requester data)
                            {:principal "dashboard" :role :operator})
              action (create-fn action-type
                                {:target-type :workflow :target-id workflow-id}
                                requester
                                {:justification (:action/justification data)
                                 :parameters (:action/parameters data)})
              auth-result (auth-fn roles action requester)]
          (if (:authorized? auth-result)
            (let [result (exec-fn es action
                                  (fn [act]
                                    ;; Execute via existing command infrastructure
                                    (let [cmd (name (:action/type act))]
                                      (write-command-file! workflow-id cmd)
                                      (state/enqueue-command! state workflow-id cmd)
                                      {:command cmd :workflow-id workflow-id})))]
              (responses/json-response {:status "executed" :result result}))
            {:status 403
             :headers {"Content-Type" "application/json"}
             :body (json/generate-string {:error "Unauthorized" :reason (:reason auth-result)})}))
        ;; Legacy command format — delegate to existing handler
        (handle-api-workflow-command state workflow-id body)))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error (str "Bad request: " (.getMessage e))})})))
