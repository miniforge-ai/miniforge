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

(ns ai.miniforge.tui-views.schema
  "Malli schemas for TUI model data structures.

   Defines schemas for data shapes introduced by the TUI layer:
   workflow detail, agent risk assessments, chat actions, PR info
   from workflow completion, and the workflow-PR reverse index.

   Layer 0 — no dependencies on other tui-views namespaces.")

;------------------------------------------------------------------------------ Layer 0
;; Risk levels used by both mechanical and agent risk

(def risk-levels [:enum :low :medium :high :critical :unknown])

;------------------------------------------------------------------------------ Layer 0a
;; Agent risk assessment (from fleet-level LLM triage)

(def AgentRiskAssessment
  "Per-PR risk assessment from fleet-level LLM triage."
  [:map
   [:level risk-levels]
   [:reason :string]])

(def AgentRiskMap
  "Map of PR identifiers to agent risk assessments.
   Key is [repo-string pr-number]."
  [:map-of
   [:tuple :string :int]
   AgentRiskAssessment])

;------------------------------------------------------------------------------ Layer 0b
;; PR info from workflow completion

(def WorkflowPrInfo
  "PR info attached to a workflow after its release phase creates a PR.
   Carried through the event stream from workflow completion."
  [:map
   [:pr-number {:optional true} [:maybe pos-int?]]
   [:pr-url {:optional true} [:maybe :string]]
   [:branch {:optional true} [:maybe :string]]
   [:commit-sha {:optional true} [:maybe :string]]])

(def WorkflowPrIndex
  "Reverse index mapping [repo, pr-number] to workflow-id.
   Built from workflow completion events carrying PR info."
  [:map-of
   [:tuple :string pos-int?]
   uuid?])

;------------------------------------------------------------------------------ Layer 0c
;; Chat action (suggested by LLM in agent panel)

(def ChatAction
  "A suggested action parsed from LLM response [ACTION: type | label | desc]."
  [:map
   [:action keyword?]
   [:label :string]
   [:description :string]])

;------------------------------------------------------------------------------ Layer 0d
;; Chat thread state

(def ChatMessage
  "A single chat message in the agent conversation."
  [:map
   [:role [:enum :user :assistant]]
   [:content :string]
   [:timestamp {:optional true} inst?]])

(def ChatState
  "State of an active chat thread."
  [:map
   [:messages [:vector ChatMessage]]
   [:input-buf :string]
   [:context [:map-of keyword? any?]]
   [:pending? boolean?]
   [:pending-since {:optional true} [:maybe pos-int?]]
   [:suggested-actions [:vector ChatAction]]
   [:scroll-offset {:optional true} [:maybe int?]]])

;------------------------------------------------------------------------------ Layer 0e
;; Workflow detail (TUI-specific view model)

(def WorkflowDetail
  "Detail state for a workflow drill-down view."
  [:map
   [:workflow-id {:optional true} [:maybe uuid?]]
   [:phases [:vector [:map
                      [:phase keyword?]
                      [:status keyword?]]]]
   [:current-phase {:optional true} [:maybe keyword?]]
   [:current-agent {:optional true} [:maybe [:map-of keyword? any?]]]
   [:agent-output :string]
   [:evidence {:optional true} [:maybe map?]]
   [:artifacts [:vector any?]]
   [:expanded-nodes set?]
   [:focused-pane int?]
   [:selected-pr {:optional true} [:maybe map?]]
   [:pr-readiness {:optional true} [:maybe map?]]
   [:pr-risk {:optional true} [:maybe map?]]
   [:selected-train {:optional true} [:maybe map?]]
   [:duration-ms {:optional true} [:maybe pos-int?]]
   [:error {:optional true} [:maybe any?]]])

;------------------------------------------------------------------------------ Layer 0f
;; Triage summary (input to fleet risk triage LLM call)

(def TriageSummary
  "Compact PR summary sent to the LLM for fleet-level risk assessment."
  [:map
   [:id [:tuple :string :int]]
   [:summary :string]])

;------------------------------------------------------------------------------ Layer 0g
;; Workflow summary (TUI model row)

(def WorkflowSummary
  "Workflow row in the TUI model :workflows vector."
  [:map
   [:id uuid?]
   [:name :string]
   [:status [:enum :pending :running :success :failed :completed :completed-with-warnings :cancelled]]
   [:phase {:optional true} [:maybe keyword?]]
   [:progress int?]
   [:started-at {:optional true} [:maybe inst?]]
   [:agents [:map-of keyword? any?]]
   [:gate-results [:vector any?]]
   [:pr-info {:optional true} [:maybe WorkflowPrInfo]]
   [:duration-ms {:optional true} [:maybe pos-int?]]
   [:error {:optional true} [:maybe any?]]])
