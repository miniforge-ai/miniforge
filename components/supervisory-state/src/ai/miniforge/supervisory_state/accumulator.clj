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

(defn- now
  "Wall-clock instant. Indirected through a fn so tests can with-redefs it."
  []
  (java.util.Date.))

(defn- event-instant
  "Read `:event/timestamp`, defaulting to wall clock if absent."
  [event]
  (or (:event/timestamp event) (now)))

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
                     (or (:message event) ""))
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
  (let [ts (event-instant event)]
    (-> table
        (ensure-workflow id ts)
        (assoc-in [:workflows id :workflow-run/updated-at] ts))))

(defn workflow-completed
  [table {:workflow/keys [id] :as event}]
  (let [ts (event-instant event)]
    (-> table
        (ensure-workflow id ts)
        (update-in [:workflows id] merge
                   {:workflow-run/status     :completed
                    :workflow-run/updated-at ts}))))

(defn workflow-failed
  [table {:workflow/keys [id] :as event}]
  (let [ts (event-instant event)]
    (-> table
        (ensure-workflow id ts)
        (update-in [:workflows id] merge
                   {:workflow-run/status     :failed
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
               :agent/heartbeat-interval-ms 30000
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
  [table {:cp/keys [agent-id status] :as event}]
  (let [ts (event-instant event)]
    (cond-> table
      (get-in table [:agents agent-id])
      (update-in [:agents agent-id] merge
                 (cond-> {:agent/last-heartbeat ts}
                   status (assoc :agent/status status))))))

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
  [table {:pr/keys [repo number url branch title author merge-order] :as event}]
  (assoc-in table [:prs (pr-key event)]
            {:pr/repo       (or repo "")
             :pr/number     (or number 0)
             :pr/url        (or url "")
             :pr/branch     (or branch "")
             :pr/title      (or title "")
             :pr/status     :open
             :pr/merge-order (or merge-order 0)
             :pr/depends-on []
             :pr/blocks     []
             :pr/ci-status  :pending
             :pr/author     author}))

(defn pr-merged
  [table event]
  (let [k (pr-key event)
        ts (event-instant event)]
    (cond-> table
      (get-in table [:prs k])
      (update-in [:prs k] merge
                 {:pr/status :merged
                  :pr/merged-at ts}))))

(defn pr-closed
  [table event]
  (let [k (pr-key event)]
    (cond-> table
      (get-in table [:prs k])
      (assoc-in [:prs k :pr/status] :closed))))

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
  [table {:pr/keys [repo number readiness risk policy recommendation] :as event}]
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
                   recommendation (assoc :pr/recommendation recommendation))]
    (assoc-in table [:prs k] scored)))

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

;------------------------------------------------------------------------------ Layer 3
;; Dispatch table — events not listed are no-ops at the entity-state level

(def handlers
  "Map of `:event/type` → handler fn `(table, event) -> table'`."
  {:workflow/started                       workflow-started
   :workflow/phase-started                 workflow-phase-started
   :workflow/phase-completed               workflow-phase-completed
   :workflow/completed                     workflow-completed
   :workflow/failed                        workflow-failed
   :control-plane/agent-registered         cp-agent-registered
   :control-plane/agent-state-changed      cp-agent-state-changed
   :control-plane/status-changed           cp-agent-state-changed
   :control-plane/agent-heartbeat          cp-agent-heartbeat
   :agent/status                           agent-status
   :pr/created                             pr-created
   :pr/merged                              pr-merged
   :pr/closed                              pr-closed
   :pr/scored                              pr-scored
   :gate/passed                            gate-passed
   :gate/failed                            gate-failed
   ;; Snapshot events (used during replay)
   :supervisory/workflow-upserted          supervisory-workflow-upserted
   :supervisory/agent-upserted             supervisory-agent-upserted
   :supervisory/pr-upserted                supervisory-pr-upserted
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
