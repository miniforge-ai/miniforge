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
   [ai.miniforge.supervisory-state.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas (re-exports)

(def WorkflowRun      schema/WorkflowRun)
(def AgentSession     schema/AgentSession)
(def PrFleetEntry     schema/PrFleetEntry)
(def PolicyEvaluation schema/PolicyEvaluation)
(def PolicyViolation  schema/PolicyViolation)
(def AttentionItem    schema/AttentionItem)
(def InterventionRequest schema/InterventionRequest)

;------------------------------------------------------------------------------ Layer 1
;; Lifecycle

(def create  core/create)
(def start!  core/start!)
(def stop!   core/stop!)
(def attach! core/attach!)
(def attached? core/attached?)
(def ensure-attached! core/ensure-attached!)

;------------------------------------------------------------------------------ Layer 2
;; Query API (reads only; consumers should prefer subscribing to
;; `:supervisory/*-upserted` events on the event stream)

(def table         core/table)
(def workflows     core/workflows)
(def agents        core/agents)
(def prs           core/prs)
(def policy-evals  core/policy-evals)
(def attention     core/attention)
(def tasks         core/tasks)
(def decisions     core/decisions)
(def interventions core/interventions)

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
