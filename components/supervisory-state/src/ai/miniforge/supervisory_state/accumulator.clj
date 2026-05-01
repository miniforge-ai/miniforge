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

(ns ai.miniforge.supervisory-state.accumulator
  "Pure event → entity-table reducer.

   Each handler is a pure fn `(table, event) -> table'`. Handlers are
   registered in the dispatch table at the bottom; `apply-event` looks the
   handler up by `:event/type` and returns the table unchanged if no handler
   is registered (the event is observed elsewhere — e.g. counted — but does
   not affect any v1 entity).

   This namespace knows nothing about emission, persistence, or threading.
   It is a pure function from an event-stream prefix to an EntityTable."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- workflow-key-fallback
  "Synthesize a workflow-key from a UUID when the event doesn't carry one.

   Live miniforge events for `:workflow/started` carry only `:workflow/id`
   and message — no workflow-key field. We render a stable, recognizable
   short label so the operator sees something useful before the first phase
   event arrives."
  [workflow-id]
  (let [s (str workflow-id)]
    (str "wf-" (subs s 0 (min 8 (count s))))))

(def ^:private default-agent-heartbeat-interval-ms
  30000)

(defn- now
  "Wall-clock instant. Indirected through a fn so tests can with-redefs it."
  []
  (java.util.Date.))

(defn- event-instant
  "Read `:event/timestamp`, defaulting to wall clock if absent."
  [event]
  (or (:event/timestamp event) (now)))

(defn- append-distinct
  "Append values from `new-items` onto `existing-items`, preserving order and
   removing duplicates."
  [existing-items new-items]
  (reduce (fn [acc item]
            (if (some #(= item %) acc)
              acc
              (conj acc item)))
          (vec (or existing-items []))
          new-items))

(defn- artifact-id
  "Extract the UUID artifact id from a phase artifact entry.

   Historical emitters have used both bare UUIDs and `{:artifact/id ...}`
   maps in phase results; supervisory-state accepts either so replay stays
   robust while the event contract converges."
  [artifact]
  (cond
    (uuid? artifact) artifact
    (uuid? (:artifact/id artifact)) (:artifact/id artifact)
    (uuid? (:id artifact)) (:id artifact)
    :else nil))

(defn- event-artifact-ids
  "Normalize :phase/artifacts from an event into a vector of UUIDs."
  [event]
  (->> (:phase/artifacts event)
       (keep artifact-id)
       vec))

(defn- repo-from-pr-url
  "Extract `owner/repo` from a GitHub pull-request URL."
  [pr-url]
  (when (string? pr-url)
    (let [parts (str/split pr-url #"/")]
      (when-let [pull-index (first (keep-indexed #(when (= %2 "pull") %1) parts))]
        (when (>= pull-index 2)
          (str (nth parts (- pull-index 2)) "/" (nth parts (dec pull-index))))))))

(defn- normalize-workflow-pr
  "Normalize workflow-owned PR metadata into the shared `:pr/*` shape."
  [pr]
  (let [repo   (or (:pr/repo pr) (repo-from-pr-url (or (:pr/url pr) (:pr-url pr))))
        number (or (:pr/number pr) (:pr-number pr) (:pr/id pr))
        url    (or (:pr/url pr) (:pr-url pr))]
    (when (and repo number url)
      (cond-> {:pr/repo repo
               :pr/number number
               :pr/url url
               :pr/branch (or (:pr/branch pr) (:branch pr) "")}
        (:pr/title pr) (assoc :pr/title (:pr/title pr))
        (:title pr)    (assoc :pr/title (:title pr))
        (:pr/author pr) (assoc :pr/author (:pr/author pr))
        (:author pr)    (assoc :pr/author (:author pr))
        (:pr/merge-order pr) (assoc :pr/merge-order (:pr/merge-order pr))
        (:merge-order pr)    (assoc :pr/merge-order (:merge-order pr))))))

(defn- event-workflow-prs
  "Collect normalized PRs embedded on workflow lifecycle events."
  [event]
  (let [raw-prs (concat (get event :workflow/pr-infos [])
                        (cond-> [] (:workflow/pr-info event) (conj (:workflow/pr-info event))))]
    (->> raw-prs
         (keep normalize-workflow-pr)
         (append-distinct []))))

(defn- coarse-agent-status
  "Map fine-grained `:agent/status` `status/type` keywords to the coarse
   AgentSession `:agent/status` enum.

   Unknown status types pass through as `:executing` (the agent is doing
   something, even if we don't have a specific mapping)."
  [status-type]
  (case status-type
    (:thinking :generating :executing :running) :executing
    (:idle :paused) :idle
    :starting :starting
    :blocked :blocked
    :completed :completed
    (:failed :error) :failed
    :unreachable :unreachable
    :executing))

;------------------------------------------------------------------------------ Layer 1
;; WorkflowRun handlers

(defn workflow-started
  [table {:workflow/keys [id] :as event}]
  (let [existing (get-in table [:workflows id])
        ts       (event-instant event)
        spec     (:workflow/spec event)
        intent   (or (some-> event :workflow/intent str)
                     (:message event)
                     "")
        wf-key   (or (:workflow/key spec)
                     (some-> spec :workflow/spec name)
                     (workflow-key-fallback id))
        run      (merge {:workflow-run/id              id
                         :workflow-run/workflow-key    wf-key
                         :workflow-run/intent          intent
                         :workflow-run/status          :running
                         :workflow-run/current-phase   :unknown
                         :workflow-run/started-at      ts
                         :workflow-run/updated-at      ts
                         :workflow-run/trigger-source  :cli
                         :workflow-run/correlation-id  id}
                        ;; Preserve fields from a prior phase-* event that
                        ;; arrived before workflow/started.
                        (select-keys existing
                                     [:workflow-run/current-phase
                                      :workflow-run/workflow-key]))]
    (assoc-in table [:workflows id] run)))

(defn- ensure-workflow
  "Create a placeholder WorkflowRun if we see a phase event before
   workflow/started — preserves event ordering robustness."
  [table workflow-id ts]
  (cond-> table
    (not (get-in table [:workflows workflow-id]))
    (assoc-in [:workflows workflow-id]
              {:workflow-run/id              workflow-id
               :workflow-run/workflow-key    (workflow-key-fallback workflow-id)
               :workflow-run/intent          ""
               :workflow-run/status          :running
               :workflow-run/current-phase   :unknown
               :workflow-run/started-at      ts
               :workflow-run/updated-at      ts
               :workflow-run/trigger-source  :cli
               :workflow-run/correlation-id  workflow-id})))

(defn workflow-phase-started
  [table {:workflow/keys [id phase] :as event}]
  (let [ts (event-instant event)]
    (-> table
        (ensure-workflow id ts)
        (update-in [:workflows id] merge
                   {:workflow-run/current-phase (or phase :unknown)
                    :workflow-run/updated-at    ts}))))

(defn workflow-phase-completed
  [table {:workflow/keys [id] :as event}]
  (let [ts (event-instant event)
        artifact-ids (event-artifact-ids event)]
    (-> table
        (ensure-workflow id ts)
        (update-in [:workflows id]
                   (fn [run]
                     (cond-> (assoc run :workflow-run/updated-at ts)
                       (seq artifact-ids)
                       (update :workflow-run/artifact-ids append-distinct artifact-ids)))))))

(defn workflow-completed
  [table {:workflow/keys [id] :as event}]
  (let [ts (event-instant event)
        prs (event-workflow-prs event)
        evidence-bundle-id (:workflow/evidence-bundle-id event)]
    (-> table
        (ensure-workflow id ts)
        (update-in [:workflows id]
                   (fn [run]
                     (cond-> (merge run
                                    {:workflow-run/status     :completed
                                     :workflow-run/updated-at ts})
                       (seq prs)
                       (update :workflow-run/prs append-distinct prs)
                       evidence-bundle-id
                       (assoc :workflow-run/evidence-bundle-id evidence-bundle-id)))))))

(defn workflow-failed
  [table {:workflow/keys [id] :as event}]
  (let [ts (event-instant event)]
    (-> table
        (ensure-workflow id ts)
        (update-in [:workflows id] merge
                   {:workflow-run/status     :failed
                    :workflow-run/updated-at ts}))))

(defn workflow-cancelled
  [table {:workflow/keys [id] :as event}]
  (let [ts (event-instant event)]
    (-> table
        (ensure-workflow id ts)
        (update-in [:workflows id] merge
                   {:workflow-run/status     :cancelled
                    :workflow-run/updated-at ts}))))

;------------------------------------------------------------------------------ Layer 1
;; AgentSession handlers

(defn cp-agent-registered
  [table {:cp/keys [agent-id vendor agent-name external-id capabilities] :as event}]
  (let [ts (event-instant event)]
    (assoc-in table [:agents agent-id]
              {:agent/id                   agent-id
               :agent/vendor               (some-> vendor name)
               :agent/external-id          (or external-id "")
               :agent/name                 (or agent-name (some-> vendor name) "")
               :agent/status               :idle
               :agent/capabilities         (or capabilities [])
               :agent/heartbeat-interval-ms default-agent-heartbeat-interval-ms
               :agent/metadata             (or (:cp/metadata event) {})
               :agent/tags                 []
               :agent/registered-at        ts
               :agent/last-heartbeat       ts
               :agent/task                 nil})))

(defn cp-agent-state-changed
  [table {:cp/keys [agent-id to-status] :as event}]
  (let [ts (event-instant event)]
    (cond-> table
      (get-in table [:agents agent-id])
      (update-in [:agents agent-id] merge
                 {:agent/status         (or to-status :unknown)
                  :agent/last-heartbeat ts}))))

(defn cp-agent-heartbeat
  [table {:cp/keys [agent-id status task metrics] :as event}]
  (let [ts (event-instant event)]
    (cond-> table
      (get-in table [:agents agent-id])
      (update-in [:agents agent-id] merge
                 (cond-> {:agent/last-heartbeat ts}
                   status (assoc :agent/status status)
                   task (assoc :agent/task task)
                   metrics (assoc :agent/metrics metrics))))))

(defn agent-status
  "Coarse `:agent/status` event: maps status/type to AgentSession status.
   Touches an existing agent if any; otherwise no-op (fine-grained agent
   status events for keyword `:agent/id` like `:planner` aren't full
   AgentSessions)."
  [table {:agent/keys [id] :status/keys [type] :as event}]
  (let [ts (event-instant event)]
    (cond-> table
      (and (uuid? id) (get-in table [:agents id]))
      (update-in [:agents id] merge
                 {:agent/status         (coarse-agent-status type)
                  :agent/last-heartbeat ts}))))

;------------------------------------------------------------------------------ Layer 1
;; PrFleetEntry handlers

(defn- pr-key
  [event]
  [(:pr/repo event) (:pr/number event)])

(defn pr-created
  [table {:pr/keys [repo number url branch title author merge-order] workflow-id :workflow/id :as event}]
  (let [pr-state (merge (get-in table [:prs (pr-key event)] {})
                        {:pr/repo        (or repo "")
                         :pr/number      (or number 0)
                         :pr/url         (or url "")
                         :pr/branch      (or branch "")
                         :pr/title       (or title "")
                         :pr/status      :open
                         :pr/merge-order (or merge-order 0)
                         :pr/depends-on  []
                         :pr/blocks      []
                         :pr/ci-status   :pending
                         :pr/author      author})]
    (assoc-in table [:prs (pr-key event)]
              (cond-> pr-state
                workflow-id (assoc :pr/workflow-run-id workflow-id)))))

(defn pr-merged
  [table event]
  (let [k (pr-key event)
        ts (event-instant event)
        workflow-id (:workflow/id event)]
    (cond-> table
      (get-in table [:prs k])
      (update-in [:prs k]
                 (fn [pr]
                   (cond-> (merge pr
                                  {:pr/status :merged
                                   :pr/merged-at ts})
                     (and workflow-id (nil? (:pr/workflow-run-id pr)))
                     (assoc :pr/workflow-run-id workflow-id)))))))

(defn pr-closed
  [table event]
  (let [k (pr-key event)
        workflow-id (:workflow/id event)]
    (cond-> table
      (get-in table [:prs k])
      (update-in [:prs k]
                 (fn [pr]
                   (cond-> (assoc pr :pr/status :closed)
                     (and workflow-id (nil? (:pr/workflow-run-id pr)))
                     (assoc :pr/workflow-run-id workflow-id)))))))

(defn pr-scored
  "Merge pre-computed readiness/risk/policy/recommendation fields from a
   `:pr/scored` event into the PR entity per N5-delta-2 §3.2. Only
   fields present on the event are written — absent fields preserve the
   previous value (or remain absent if never seen), so a partial re-score
   does not clobber a fuller score.

   If the PR entity does not yet exist, a minimal stub is created so the
   scored fields are not lost if `:pr/scored` is observed before
   `:pr/created`. The stub uses producer-canonical empties for required
   keys; a later `:pr/created` or `:supervisory/pr-upserted` will overwrite
   them while the scored keys are preserved via `merge`."
  [table {:pr/keys [repo number readiness risk policy recommendation] workflow-id :workflow/id :as event}]
  (let [k       (pr-key event)
        stub    {:pr/repo       (or repo "")
                 :pr/number     (or number 0)
                 :pr/url        ""
                 :pr/branch     ""
                 :pr/title      ""
                 :pr/status     :open
                 :pr/merge-order 0
                 :pr/depends-on []
                 :pr/blocks     []
                 :pr/ci-status  :pending}
        existing (get-in table [:prs k] stub)
        scored   (cond-> existing
                   readiness      (assoc :pr/readiness readiness)
                   risk           (assoc :pr/risk risk)
                   policy         (assoc :pr/policy policy)
                   recommendation (assoc :pr/recommendation recommendation)
                   (and workflow-id (nil? (:pr/workflow-run-id existing)))
                   (assoc :pr/workflow-run-id workflow-id))]
    (assoc-in table [:prs k] scored)))

;------------------------------------------------------------------------------ Layer 1
;; TaskNode handlers (N5-δ3 §2.3, §3.3)

(def task-kanban-mapping-resource
  "Classpath location of the EDN mapping from :task/status to the closed
   six-column Kanban set. Config is data — editing the EDN lets operators
   adjust the status→column projection without code changes."
  "config/supervisory-state/task-kanban-mapping.edn")

(defn load-task-kanban-mapping
  "Read the status→column map from [[task-kanban-mapping-resource]].
   Throws if the resource is missing — the file ships with the component,
   so its absence indicates a packaging bug worth failing fast on."
  []
  (if-let [r (io/resource task-kanban-mapping-resource)]
    (edn/read-string (slurp r))
    (throw (ex-info "supervisory-state: task-kanban mapping resource missing"
                    {:resource task-kanban-mapping-resource}))))

(def ^{:doc "Memoized status→column map loaded lazily from
  [[task-kanban-mapping-resource]]. Tests that need to exercise a
  different mapping `with-redefs` this var."}
  status->kanban-column
  (delay (load-task-kanban-mapping)))

(defn- task-kanban-column
  "Derive the Kanban column from a task status per N5-δ3 §2.3. Unknown
   statuses default to :blocked so new state-profile values surface
   visibly rather than silently landing in :done."
  [status]
  (get @status->kanban-column status :blocked))

(defn- task-merge-state
  "Merge a status transition into the task entry at `existing`. Keeps
   timestamps monotonic: :started-at captured on first entry to an active
   column, :completed-at captured on first entry to :done. `:elapsed-ms`
   is recomputed from `:started-at` on every update."
  [existing new-status ^java.util.Date now-inst]
  (let [column        (task-kanban-column new-status)
        started-at    (or (:task/started-at existing)
                          (when (= column :active) now-inst))
        completed-at  (or (:task/completed-at existing)
                          (when (= column :done) now-inst))
        elapsed-ms    (when started-at
                        (- (.getTime ^java.util.Date (or completed-at now-inst))
                           (.getTime ^java.util.Date started-at)))]
    (cond-> (assoc existing
                   :task/status       new-status
                   :task/kanban-column column)
      started-at   (assoc :task/started-at started-at)
      completed-at (assoc :task/completed-at completed-at)
      elapsed-ms   (assoc :task/elapsed-ms elapsed-ms))))

(defn task-state-changed
  "Handler for `:task/state-changed` (N3 §3 dag lifecycle). Produces or
   updates a TaskNode entry keyed by `:task/id`. Description / deps /
   type are populated opportunistically from `:task/context` when the
   DAG scheduler includes them; otherwise left absent and filled by
   later events.

   Per N5-δ3 §3.3: dependency resolution is the scheduler's concern —
   the accumulator trusts the `:task/to-state` it receives rather than
   recomputing blocked-vs-ready locally. `:pending` stays in `:blocked`
   until the scheduler emits `:ready`."
  [table {:task/keys [id to-state context] workflow-id :workflow/id :as event}]
  (let [task-id      id
        wf-id        workflow-id
        status       (or to-state :pending)
        now-inst     (event-instant event)
        existing     (get-in table [:tasks task-id]
                             {:task/id              task-id
                              :task/workflow-run-id wf-id
                              :task/description     ""})
        ;; Context fields from the scheduler are optional; merge any we see.
        ctx-fields   (cond-> {}
                       (:description context)     (assoc :task/description     (:description context))
                       (:type context)            (assoc :task/type            (:type context))
                       (:component context)       (assoc :task/component       (:component context))
                       (:dependencies context)    (assoc :task/dependencies    (vec (:dependencies context)))
                       (:dependents context)      (assoc :task/dependents      (vec (:dependents context)))
                       (:exclusive-files? context) (assoc :task/exclusive-files? (:exclusive-files? context))
                       (:stratum? context)        (assoc :task/stratum?        (:stratum? context)))
        merged       (-> (merge existing ctx-fields)
                         (task-merge-state status now-inst))]
    (cond-> table
      task-id (assoc-in [:tasks task-id] merged))))

;------------------------------------------------------------------------------ Layer 1
;; PolicyEvaluation handlers

(defn- normalize-violation
  [v]
  {:violation/rule-id     (or (:rule-id v) (:violation/rule-id v) :unknown)
   :violation/severity    (or (:severity v) (:violation/severity v) :info)
   :violation/category    (or (:category v) (:violation/category v) :process)
   :violation/message     (or (:message v) (:violation/message v) "")
   :violation/location    (or (:location v) (:violation/location v))
   :violation/remediable? (boolean (or (:remediable? v) (:violation/remediable? v)))})

(defn- policy-evaluation
  [{:gate/keys [id target-type target-id packs violations] :as event} passed?]
  (let [eval-id (or id (random-uuid))]
    {:policy-eval/id            eval-id
     :policy-eval/target-type   (or target-type :artifact)
     :policy-eval/target-id     (or target-id eval-id)
     :policy-eval/passed?       passed?
     :policy-eval/packs-applied (or packs [])
     :policy-eval/violations    (mapv normalize-violation (or violations []))
     :policy-eval/evaluated-at  (event-instant event)}))

(defn gate-passed
  [table event]
  (let [eval (policy-evaluation event true)]
    (assoc-in table [:policy-evals (:policy-eval/id eval)] eval)))

(defn gate-failed
  [table event]
  (let [eval (policy-evaluation event false)]
    (assoc-in table [:policy-evals (:policy-eval/id eval)] eval)))

;------------------------------------------------------------------------------ Layer 2
;; Snapshot-event handlers — applied during startup replay.
;; A `:supervisory/*-upserted` event carries the canonical entity in
;; :supervisory/entity; we trust it as the baseline state.

(defn supervisory-workflow-upserted
  [table event]
  (let [entity (:supervisory/entity event)]
    (cond-> table
      entity (assoc-in [:workflows (:workflow-run/id entity)] entity))))

(defn supervisory-agent-upserted
  [table event]
  (let [entity (:supervisory/entity event)]
    (cond-> table
      entity (assoc-in [:agents (:agent/id entity)] entity))))

(defn supervisory-pr-upserted
  [table event]
  (let [entity (:supervisory/entity event)]
    (cond-> table
      entity (assoc-in [:prs [(:pr/repo entity) (:pr/number entity)]] entity))))

(defn supervisory-policy-evaluated
  [table event]
  (let [entity (:supervisory/entity event)]
    (cond-> table
      entity (assoc-in [:policy-evals (:policy-eval/id entity)] entity))))

(defn supervisory-attention-derived
  [table event]
  (let [entity (:supervisory/entity event)]
    (cond-> table
      entity (assoc-in [:attention (:attention/id entity)] entity))))

(defn supervisory-task-node-upserted
  [table event]
  (let [entity (:supervisory/entity event)]
    (cond-> table
      entity (assoc-in [:tasks (:task/id entity)] entity))))

;------------------------------------------------------------------------------ Layer 1
;; DecisionCard handlers (N5-δ3 §2.4, §3.4)

(defn cp-decision-created
  "Handler for `:control-plane/decision-created` — creates a pending
   DecisionCard entry per N5-δ3 §2.4.

   Today's event payload carries the thin (`agent-id`, `decision-id`,
   `summary`, optional `priority`) set described in
   event-stream.core/cp-decision-created. Richer DecisionCard fields
   (`type`, `context`, `options`, `deadline`) are populated verbatim
   when future control-plane emissions carry them; absent fields stay
   off the entity per the open-map rule."
  [table event]
  (let [{:cp/keys [agent-id decision-id summary priority type context options deadline]
         :workflow/keys [id] :as _e} event
        ts (event-instant event)
        card (cond-> {:decision/id        decision-id
                      :decision/agent-id  agent-id
                      :decision/status    :pending
                      :decision/summary   (or summary "")
                      :decision/created-at ts}
               id       (assoc :decision/workflow-run-id id)
               type     (assoc :decision/type type)
               priority (assoc :decision/priority priority)
               context  (assoc :decision/context context)
               (seq options) (assoc :decision/options (vec options))
               deadline (assoc :decision/deadline deadline))]
    (cond-> table
      decision-id (assoc-in [:decisions decision-id] card))))

(defn cp-decision-resolved
  "Handler for `:control-plane/decision-resolved`. Updates the matching
   DecisionCard with resolution state + timestamp.

   If the card does not exist (resolved event arrived before the create
   event, e.g. replay gap), a minimal stub is created so the resolution
   is not lost — the later create event's fields merge in via regular
   upsert. The status transitions from `:pending` to `:resolved`;
   `:expired` is reserved for a future `:control-plane/decision-expired`
   event."
  [table event]
  (let [{:cp/keys [decision-id resolution comment] :as _e} event
        ts (event-instant event)
        stub {:decision/id decision-id
              :decision/agent-id nil
              :decision/summary ""
              :decision/created-at ts}
        existing (get-in table [:decisions decision-id] stub)
        updated  (cond-> (assoc existing
                                :decision/status      :resolved
                                :decision/resolved-at ts)
                   resolution (assoc :decision/resolution resolution)
                   comment    (assoc :decision/comment comment))]
    (cond-> table
      decision-id (assoc-in [:decisions decision-id] updated))))

(defn supervisory-decision-upserted
  [table event]
  (let [entity (:supervisory/entity event)]
    (cond-> table
      entity (assoc-in [:decisions (:decision/id entity)] entity))))

;------------------------------------------------------------------------------ Layer 1
;; InterventionRequest handlers

(defn- intervention-stub
  [event]
  (let [ts (event-instant event)]
    {:intervention/id (or (:intervention/id event) (random-uuid))
     :intervention/type :request-human-review
     :intervention/target-type :supervision
     :intervention/target-id nil
     :intervention/requested-by "unknown"
     :intervention/request-source :api
     :intervention/state :proposed
     :intervention/requested-at ts
     :intervention/updated-at ts}))

(defn- intervention-attributes
  [event requested-at updated-at]
  (cond-> {:intervention/id (:intervention/id event)
           :intervention/state (get event :intervention/state :proposed)
           :intervention/requested-at requested-at
           :intervention/updated-at updated-at}
    (:intervention/type event)
    (assoc :intervention/type (:intervention/type event))

    (:intervention/target-type event)
    (assoc :intervention/target-type (:intervention/target-type event))

    (contains? event :intervention/target-id)
    (assoc :intervention/target-id (:intervention/target-id event))

    (:intervention/requested-by event)
    (assoc :intervention/requested-by (:intervention/requested-by event))

    (:intervention/request-source event)
    (assoc :intervention/request-source (:intervention/request-source event))

    (:intervention/justification event)
    (assoc :intervention/justification (:intervention/justification event))

    (:intervention/details event)
    (assoc :intervention/details (:intervention/details event))

    (:intervention/reason event)
    (assoc :intervention/reason (:intervention/reason event))

    (:intervention/outcome event)
    (assoc :intervention/outcome (:intervention/outcome event))

    (contains? event :intervention/approval-required?)
    (assoc :intervention/approval-required?
           (:intervention/approval-required? event))))

(defn supervisory-intervention-requested
  [table event]
  (let [intervention-id (:intervention/id event)
        ts (or (:intervention/requested-at event) (event-instant event))
        request (intervention-attributes event ts (or (:intervention/updated-at event) ts))]
    (cond-> table
      intervention-id (assoc-in [:interventions intervention-id] request))))

(defn supervisory-intervention-state-changed
  [table event]
  (let [intervention-id (:intervention/id event)
        existing (get-in table [:interventions intervention-id] (intervention-stub event))
        updated-at (or (:intervention/updated-at event) (event-instant event))
        updated (merge existing
                       (intervention-attributes event
                                                (or (:intervention/requested-at event)
                                                    (:intervention/requested-at existing))
                                                updated-at)
                       {:intervention/state (or (:intervention/state event)
                                                (:intervention/state existing)
                                                :proposed)
                        :intervention/updated-at updated-at})]
    (cond-> table
      intervention-id (assoc-in [:interventions intervention-id] updated))))

(defn supervisory-intervention-upserted
  [table event]
  (let [entity (:supervisory/entity event)]
    (cond-> table
      entity (assoc-in [:interventions (:intervention/id entity)] entity))))

(defn- dependency-entity
  [event]
  (or (:supervisory/entity event)
      (:dependency/entity event)
      (select-keys event
                   [:dependency/id
                    :dependency/source
                    :dependency/kind
                    :dependency/status
                    :dependency/failure-count
                    :dependency/window-size
                    :dependency/incident-counts
                    :dependency/vendor
                    :dependency/class
                    :dependency/retryability
                    :failure/class
                    :dependency/last-observed-at
                    :dependency/last-recovered-at])))

(defn- resolved-dependency-source
  [event existing]
  (or (:dependency/source event)
      (:dependency/source existing)))

(defn- resolved-dependency-kind
  [event existing]
  (or (:dependency/kind event)
      (:dependency/kind existing)))

(defn- recovered-dependency-entity
  [event existing recovered-at]
  (let [dependency-id (:dependency/id event)
        source (resolved-dependency-source event existing)
        kind (resolved-dependency-kind event existing)
        vendor (or (:dependency/vendor event)
                   (:dependency/vendor existing))
        window-size (or (:dependency/window-size event)
                        (:dependency/window-size existing)
                        0)
        last-observed-at (:dependency/last-observed-at existing)
        dependency-class (:dependency/class existing)
        retryability (:dependency/retryability existing)
        failure-class (:failure/class existing)]
    (when dependency-id
      (cond-> {:dependency/id dependency-id
               :dependency/source source
               :dependency/kind kind
               :dependency/status :healthy
               :dependency/failure-count 0
               :dependency/window-size window-size
               :dependency/incident-counts {}}
        vendor (assoc :dependency/vendor vendor)
        dependency-class (assoc :dependency/class dependency-class)
        retryability (assoc :dependency/retryability retryability)
        failure-class (assoc :failure/class failure-class)
        last-observed-at (assoc :dependency/last-observed-at last-observed-at)
        recovered-at (assoc :dependency/last-recovered-at recovered-at)))))

(defn dependency-health-updated
  [table event]
  (let [entity (dependency-entity event)
        dependency-id (:dependency/id entity)]
    (cond-> table
      dependency-id (assoc-in [:dependencies dependency-id] entity))))

(defn dependency-recovered
  [table event]
  (let [dependency-id (:dependency/id event)
        existing (get-in table [:dependencies dependency-id])
        recovered-at (or (:dependency/last-recovered-at event)
                         (:dependency/recovered-at event)
                         (event-instant event))
        updated (recovered-dependency-entity event existing recovered-at)]
    (cond-> table
      updated (assoc-in [:dependencies dependency-id] updated))))

;------------------------------------------------------------------------------ Layer 3
;; Dispatch table — events not listed are no-ops at the entity-state level

(def handlers
  "Map of `:event/type` → handler fn `(table, event) -> table'`."
  {:workflow/started                       workflow-started
   :workflow/phase-started                 workflow-phase-started
   :workflow/phase-completed               workflow-phase-completed
   :workflow/completed                     workflow-completed
   :workflow/failed                        workflow-failed
   :workflow/cancelled                     workflow-cancelled
   :control-plane/agent-registered         cp-agent-registered
   :control-plane/agent-state-changed      cp-agent-state-changed
   :control-plane/status-changed           cp-agent-state-changed
   :control-plane/agent-heartbeat          cp-agent-heartbeat
   :control-plane/decision-created         cp-decision-created
   :control-plane/decision-resolved        cp-decision-resolved
   :supervisory/intervention-requested     supervisory-intervention-requested
   :supervisory/intervention-state-changed supervisory-intervention-state-changed
   :agent/status                           agent-status
   :pr/created                             pr-created
   :pr/merged                              pr-merged
   :pr/closed                              pr-closed
   :pr/scored                              pr-scored
   :task/state-changed                     task-state-changed
   :gate/passed                            gate-passed
   :gate/failed                            gate-failed
   ;; Snapshot events (used during replay)
   :supervisory/workflow-upserted          supervisory-workflow-upserted
   :supervisory/agent-upserted             supervisory-agent-upserted
   :supervisory/pr-upserted                supervisory-pr-upserted
   :supervisory/task-node-upserted         supervisory-task-node-upserted
   :supervisory/decision-upserted          supervisory-decision-upserted
   :supervisory/intervention-upserted      supervisory-intervention-upserted
   :supervisory/dependency-upserted        dependency-health-updated
   :dependency/health-updated              dependency-health-updated
   :dependency/recovered                   dependency-recovered
   :supervisory/policy-evaluated           supervisory-policy-evaluated
   :supervisory/attention-derived          supervisory-attention-derived})

(defn apply-event
  "Apply a single event to the entity table. Returns the (possibly identical)
   updated table. Unknown event types are silently ignored at the entity
   level — they may still be observed by other consumers."
  [table event]
  (let [handler (get handlers (:event/type event))]
    (if handler
      (handler table event)
      table)))

(defn apply-events
  "Reduce a sequence of events into an entity table."
  [table events]
  (reduce apply-event table events))
