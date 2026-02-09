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

(ns ai.miniforge.event-stream.schema
  "N3-compliant event schemas for workflow observability.

   See specs/normative/N3-event-stream.md for the full specification.")

;------------------------------------------------------------------------------ Layer 0
;; Event envelope (base schema all events must conform to)

(def EventEnvelope
  "Base envelope schema for all events per N3 spec section 2."
  [:map
   [:event/type keyword?]              ; REQUIRED: event type identifier
   [:event/id uuid?]                   ; REQUIRED: unique event ID
   [:event/timestamp inst?]            ; REQUIRED: ISO-8601 timestamp
   [:event/version string?]            ; REQUIRED: event schema version
   [:event/sequence-number int?]       ; REQUIRED: monotonic sequence within workflow
   [:workflow/id uuid?]                ; REQUIRED: workflow this event belongs to
   [:workflow/phase {:optional true} keyword?]     ; OPTIONAL: current phase
   [:agent/id {:optional true} keyword?]           ; OPTIONAL: agent that emitted event
   [:agent/instance-id {:optional true} uuid?]     ; OPTIONAL: specific agent instance
   [:event/parent-id {:optional true} uuid?]       ; OPTIONAL: parent event ID (for causality)
   [:message string?]])                ; REQUIRED: human-readable message

;------------------------------------------------------------------------------ Layer 1
;; Workflow lifecycle event schemas

(def WorkflowStarted
  "Schema for workflow/started event."
  [:map
   [:event/type [:= :workflow/started]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:workflow/spec {:optional true} map?]
   [:workflow/intent {:optional true} map?]
   [:message string?]])

(def PhaseStarted
  "Schema for workflow/phase-started event."
  [:map
   [:event/type [:= :workflow/phase-started]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:workflow/phase keyword?]
   [:phase/expected-agent {:optional true} keyword?]
   [:phase/context {:optional true} map?]
   [:message string?]])

(def PhaseCompleted
  "Schema for workflow/phase-completed event."
  [:map
   [:event/type [:= :workflow/phase-completed]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:workflow/phase keyword?]
   [:phase/duration-ms {:optional true} int?]
   [:phase/outcome {:optional true} [:enum :success :failure :skipped]]
   [:phase/artifacts {:optional true} [:vector uuid?]]
   [:message string?]])

(def WorkflowCompleted
  "Schema for workflow/completed event."
  [:map
   [:event/type [:= :workflow/completed]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:workflow/status [:enum :success :failure :cancelled]]
   [:workflow/duration-ms {:optional true} int?]
   [:workflow/evidence-bundle-id {:optional true} uuid?]
   [:message string?]])

(def WorkflowFailed
  "Schema for workflow/failed event."
  [:map
   [:event/type [:= :workflow/failed]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:workflow/failure-phase {:optional true} keyword?]
   [:workflow/failure-reason {:optional true} string?]
   [:workflow/error-details {:optional true} map?]
   [:message string?]])

;------------------------------------------------------------------------------ Layer 2
;; Agent lifecycle event schemas

(def AgentStarted
  "Schema for agent/started event."
  [:map
   [:event/type [:= :agent/started]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:workflow/phase {:optional true} keyword?]
   [:agent/id keyword?]
   [:agent/instance-id {:optional true} uuid?]
   [:agent/context {:optional true} map?]
   [:message string?]])

(def AgentCompleted
  "Schema for agent/completed event."
  [:map
   [:event/type [:= :agent/completed]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:agent/id keyword?]
   [:agent/instance-id {:optional true} uuid?]
   [:agent/duration-ms {:optional true} int?]
   [:agent/outcome {:optional true} [:enum :success :failure]]
   [:agent/output {:optional true} map?]
   [:agent/artifacts {:optional true} [:vector uuid?]]
   [:message string?]])

(def AgentChunk
  "Schema for agent/chunk event (streaming output)."
  [:map
   [:event/type [:= :agent/chunk]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:agent/id keyword?]
   [:chunk/delta string?]              ; The chunk of text
   [:chunk/done? {:optional true} boolean?]
   [:message string?]])

(def AgentStatus
  "Schema for agent/status event (real-time progress)."
  [:map
   [:event/type [:= :agent/status]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:workflow/phase {:optional true} keyword?]
   [:agent/id keyword?]
   [:agent/instance-id {:optional true} uuid?]
   [:status/type [:enum :reading :thinking :generating :validating :repairing :running :waiting :communicating]]
   [:status/detail {:optional true} string?]
   [:status/progress-percent {:optional true} int?]
   [:message string?]])

;------------------------------------------------------------------------------ Layer 3
;; Self-healing event schemas

(def WorkaroundApplied
  "Schema for self-healing/workaround-applied event."
  [:map
   [:event/type [:= :self-healing/workaround-applied]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:workaround-id {:optional true} keyword?]
   [:pattern-id keyword?]
   [:success? boolean?]
   [:message string?]])

(def BackendSwitched
  "Schema for self-healing/backend-switched event."
  [:map
   [:event/type [:= :self-healing/backend-switched]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:from keyword?]
   [:to keyword?]
   [:reason string?]
   [:cooldown-until inst?]
   [:message string?]])

;------------------------------------------------------------------------------ Layer 4
;; LLM event schemas

(def LLMRequest
  "Schema for llm/request event."
  [:map
   [:event/type [:= :llm/request]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:agent/id {:optional true} keyword?]
   [:agent/instance-id {:optional true} uuid?]
   [:llm/model string?]
   [:llm/prompt-tokens {:optional true} int?]
   [:llm/request-id uuid?]
   [:message string?]])

(def LLMResponse
  "Schema for llm/response event."
  [:map
   [:event/type [:= :llm/response]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:agent/id {:optional true} keyword?]
   [:agent/instance-id {:optional true} uuid?]
   [:llm/model string?]
   [:llm/request-id uuid?]
   [:llm/completion-tokens {:optional true} int?]
   [:llm/total-tokens {:optional true} int?]
   [:llm/duration-ms {:optional true} int?]
   [:llm/cost-usd {:optional true} number?]
   [:message string?]])

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example event
  {:event/type :workflow/started
   :event/id (random-uuid)
   :event/timestamp (java.util.Date.)
   :event/version "1.0.0"
   :event/sequence-number 0
   :workflow/id (random-uuid)
   :message "Workflow started"}

  :leave-this-here)
