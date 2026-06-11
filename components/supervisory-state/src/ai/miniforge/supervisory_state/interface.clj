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

(ns ai.miniforge.supervisory-state.interface
  "Public API for the supervisory-state component.

   Materializes the five canonical supervisory entities from
   N5-delta-supervisory-control-plane §3 out of the fine-grained event
   stream (N3 §3.1–§3.16) and emits `:supervisory/*-upserted` snapshot events
   (N3 §3.19) for consumers — the Rust control console, native app, and web
   dashboard."
  (:require
   [ai.miniforge.supervisory-state.core :as core]
   [ai.miniforge.supervisory-state.golden-fixtures :as golden-fixtures]
   [ai.miniforge.supervisory-state.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Contract version + golden fixtures

(def schema-version
  "Version of the supervisory entity contract, stamped as
   `:supervisory/schema-version` on every supervisory snapshot event
   (N3 §3.19, including `:supervisory/policy-evaluated` and
   `:supervisory/attention-derived`). Bump rules live on
   `schema/schema-version`."
  schema/schema-version)

(def write-golden-fixtures!
  "Write the canonical golden fixtures — one pinned, schema-validated
   `:supervisory/*-upserted` event per entity family, serialized through
   the production transit encoder — plus `manifest.edn` into `:out-dir`.
   Consumed (vendored) by the miniforge-control supervisory-entities
   crate as the cross-language contract test corpus. `clojure -X`
   compatible; see the `fixtures:supervisory` bb task."
  golden-fixtures/write-golden-fixtures!)

;------------------------------------------------------------------------------ Layer 0
;; Schemas (re-exports)

(def Spec
  "Malli `[:map ...]` schema (open) for a long-lived Spec — the operator's
   top-level unit of work that owns N WorkflowRuns over its lifetime
   (N5-delta-3 §3.1, §5.1)."
  schema/Spec)

(def WorkflowRun
  "Malli `[:map ...]` schema (open) for a WorkflowRun — one concrete
   execution instance of a workflow (N5-delta-1 §3.1)."
  schema/WorkflowRun)

(def AgentSession
  "Malli `[:map ...]` schema (open) for an AgentSession — an external or
   internal agent observable to the supervisory plane (N5-delta-1 §3.3)."
  schema/AgentSession)

(def PrFleetEntry
  "Malli `[:map ...]` schema (open) for a PrFleetEntry — a pull request in
   the supervisory PR fleet view, with optional pre-computed readiness/risk/
   policy/recommendation scores (N5-delta-2)."
  schema/PrFleetEntry)

(def PolicyEvaluation
  "Malli `[:map ...]` schema (open) for a PolicyEvaluation — an immutable
   record of a completed policy evaluation (N5-delta-1 §3.1)."
  schema/PolicyEvaluation)

(def PolicyViolation
  "Malli `[:map ...]` schema (open) for a PolicyViolation — a single rule
   failure within a PolicyEvaluation (N5-delta-1 §3.1)."
  schema/PolicyViolation)

(def AttentionItem
  "Malli `[:map ...]` schema (open) for an AttentionItem — a derived
   supervisory signal (N5-delta-1 §3.1 + §5)."
  schema/AttentionItem)

(def InterventionRequest
  "Malli `[:map ...]` schema (open) for an InterventionRequest — a bounded
   supervisory control request (N5 supervisory delta §3.1)."
  schema/InterventionRequest)

;------------------------------------------------------------------------------ Layer 1
;; Lifecycle

(def create
  "Build a fresh supervisory-state component instance bound to `stream`.
   Returns an atom holding the component state; does not subscribe yet —
   call `start!` to begin consuming events."
  core/create)

(def start!
  "Subscribe the component to its stream so it begins materializing the
   entity table from live events. Idempotent — repeat calls are no-ops.
   Returns the component. No startup replay of the event log."
  core/start!)

(def stop!
  "Unsubscribe the component from its stream. The entity table is retained
   so it stays queryable post-shutdown. Returns the component."
  core/stop!)

(def attach!
  "Create a component bound to `stream` and start it in one step
   (create + start!). Returns the started component."
  core/attach!)

(def attached?
  "True when supervisory-state is already subscribed to `stream`; false
   otherwise. Returns boolean."
  core/attached?)

(def ensure-attached!
  "Attach supervisory-state to `stream` exactly once, synchronized per
   stream so concurrent callers cannot double-attach. Returns a new
   component when an attachment is created; otherwise nil."
  core/ensure-attached!)

;------------------------------------------------------------------------------ Layer 2
;; Query API (reads only; consumers should prefer subscribing to
;; `:supervisory/*-upserted` events on the event stream)

(def table
  "Current entity table snapshot for `component` (pure read). Returns the
   EntityTable map: keys `:specs :workflows :agents :prs :policy-evals
   :attention :tasks :decisions :interventions :dependencies`, each a map
   of id -> entity."
  core/table)

(def specs
  "All Spec entities in `component`. Returns a seq of Spec maps (empty when
   none)."
  core/specs)

(def workflows
  "All WorkflowRun entities in `component`. Returns a seq of WorkflowRun
   maps (empty when none)."
  core/workflows)

(def agents
  "All AgentSession entities in `component`. Returns a seq of AgentSession
   maps (empty when none)."
  core/agents)

(def prs
  "All PrFleetEntry entities in `component`. Returns a seq of PrFleetEntry
   maps (empty when none)."
  core/prs)

(def policy-evals
  "All PolicyEvaluation entities in `component`. Returns a seq of
   PolicyEvaluation maps (empty when none)."
  core/policy-evals)

(def attention
  "All derived AttentionItem entities in `component`. Returns a seq of
   AttentionItem maps (empty when none)."
  core/attention)

(def tasks
  "All TaskNode entities in `component`. Returns a seq of TaskNode maps
   (empty when none)."
  core/tasks)

(def decisions
  "All DecisionCard entities in `component`. Returns a seq of DecisionCard
   maps (empty when none)."
  core/decisions)

(def interventions
  "All InterventionRequest entities in `component`. Returns a seq of
   InterventionRequest maps (empty when none)."
  core/interventions)

(comment
  (require '[ai.miniforge.event-stream.interface :as es])

  ;; Attach a supervisor to a throwaway stream and watch the tables
  ;; populate as fine-grained events publish.
  (def stream (es/create-event-stream {:sinks []}))
  (def supervisor (attach! stream))

  (let [wf (random-uuid)]
    (es/publish! stream (es/workflow-started stream wf))
    (es/publish! stream (es/workflow-completed stream wf :success)))

  (workflows supervisor)
  (attention supervisor)

  (stop! supervisor)
  :rcf)
