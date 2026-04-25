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
;; Shared payload schemas

(def TransitionRequest
  "Schema for a phase transition request payload."
  [:map
   [:transition/type keyword?]
   [:transition/target keyword?]])

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
   [:phase/transition-request {:optional true} TransitionRequest]
   [:phase/redirect-to {:optional true} keyword?]
   [:phase/error {:optional true} map?]
   [:phase/tokens {:optional true} int?]
   [:phase/cost-usd {:optional true} number?]
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
   [:workflow/tokens {:optional true} int?]
   [:workflow/cost-usd {:optional true} number?]
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

;------------------------------------------------------------------------------ Layer 1.5
;; PR lifecycle event schemas

(def PRCreated
  "Schema for pr/created event.

   Emitted when a workflow creates a PR that should appear in the
   supervisory PR fleet. `:workflow/id` is the owning workflow run, which
   lets downstream consumers correlate the PR back to the run/spec dossier."
  [:map
   [:event/type [:= :pr/created]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:pr/repo string?]
   [:pr/number int?]
   [:pr/url string?]
   [:pr/branch string?]
   [:pr/title {:optional true} string?]
   [:pr/author {:optional true} string?]
   [:pr/merge-order {:optional true} int?]
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

;------------------------------------------------------------------------------ Layer 5
;; Privacy levels and OCI event schemas (N8)

(def PrivacyLevel
  "Privacy classification for events.
   - :public    — safe for external dashboards and audit logs
   - :internal  — visible to team members and internal tools
   - :confidential — restricted to control-plane operators only"
  [:enum :public :internal :confidential])

(def default-event-privacy
  "Built-in privacy level for each event type category.
   Events not listed default to :internal."
  {:workflow/started    :public
   :workflow/completed  :public
   :workflow/failed     :public
   :workflow/phase-started   :public
   :workflow/phase-completed :public
   :workflow/milestone-reached :public
   :agent/started    :internal
   :agent/completed  :internal
   :agent/failed     :internal
   :agent/status     :internal
   :agent/chunk      :internal
   :gate/started     :public
   :gate/passed      :public
   :gate/failed      :public
   :tool/invoked     :internal
   :tool/completed   :internal
   :llm/request      :internal
   :llm/response     :internal
   :supervision/tool-use-evaluated :internal
   :supervisory/intervention-requested :confidential
   :supervisory/intervention-state-changed :confidential
   :control-action/requested :confidential
   :control-action/executed  :confidential
   :annotation/created       :internal
   :listener/attached        :internal
   :listener/detached        :internal
   :task/state-changed       :internal
   :task/frontier-entered    :internal
   :task/skip-propagated     :internal
   :chain/started            :public
   :chain/completed          :public
   :chain/failed             :public
   :chain/step-started       :internal
   :chain/step-completed     :internal
   :chain/step-failed        :internal})

(defn create-privacy-config
  "Create a privacy configuration by merging overrides into defaults.

   Arguments:
   - overrides: Map of event-type -> privacy-level to override defaults

   Returns: Map of event-type -> privacy-level"
  [overrides]
  (merge default-event-privacy overrides))

(defn event-privacy
  "Get the privacy level for an event type.

   Arguments:
   - event-type: Event type keyword
   - config: Optional privacy config from create-privacy-config.
             Uses built-in defaults when not provided.

   Defaults to :internal for unlisted event types."
  ([event-type]
   (get default-event-privacy event-type :internal))
  ([event-type config]
   (get config event-type :internal)))

;; Supervision event schema

(def ToolUseEvaluated
  "Schema for supervision/tool-use-evaluated event."
  [:map
   [:event/type [:= :supervision/tool-use-evaluated]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:tool/name string?]
   [:supervision/decision string?]
   [:supervision/reasoning {:optional true} string?]
   [:supervision/meta-eval? {:optional true} boolean?]
   [:supervision/confidence {:optional true} number?]
   [:workflow/phase {:optional true} keyword?]
   [:message string?]])

(def InterventionRequested
  "Schema for supervisory/intervention-requested event."
  [:map
   [:event/type [:= :supervisory/intervention-requested]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id {:optional true} [:maybe uuid?]]
   [:intervention/id uuid?]
   [:intervention/type keyword?]
   [:intervention/target-type keyword?]
   [:intervention/target-id any?]
   [:intervention/requested-by string?]
   [:intervention/request-source keyword?]
   [:intervention/state keyword?]
   [:intervention/justification {:optional true} string?]
   [:intervention/details {:optional true} map?]
   [:intervention/approval-required? {:optional true} boolean?]
   [:intervention/requested-at inst?]
   [:intervention/updated-at inst?]
   [:message string?]])

(def InterventionStateChanged
  "Schema for supervisory/intervention-state-changed event."
  [:map
   [:event/type [:= :supervisory/intervention-state-changed]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id {:optional true} [:maybe uuid?]]
   [:intervention/id uuid?]
   [:intervention/state keyword?]
   [:intervention/from-state {:optional true} keyword?]
   [:intervention/type {:optional true} keyword?]
   [:intervention/target-type {:optional true} keyword?]
   [:intervention/target-id {:optional true} any?]
   [:intervention/requested-by {:optional true} string?]
   [:intervention/request-source {:optional true} keyword?]
   [:intervention/justification {:optional true} string?]
   [:intervention/details {:optional true} map?]
   [:intervention/reason {:optional true} string?]
   [:intervention/outcome {:optional true} any?]
   [:intervention/approval-required? {:optional true} boolean?]
   [:intervention/requested-at {:optional true} inst?]
   [:intervention/updated-at {:optional true} inst?]
   [:message string?]])

;; OCI container event schemas

(def ContainerStarted
  "Schema for oci/container-started event."
  [:map
   [:event/type [:= :oci/container-started]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:oci/container-id string?]
   [:oci/image-digest {:optional true} string?]
   [:oci/trust-level {:optional true} [:enum :untrusted :trusted :privileged]]
   [:message string?]])

(def ContainerCompleted
  "Schema for oci/container-completed event."
  [:map
   [:event/type [:= :oci/container-completed]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]
   [:oci/container-id string?]
   [:oci/exit-code int?]
   [:oci/duration-ms {:optional true} int?]
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

  ;; Privacy lookup
  (event-privacy :workflow/started) ;; => :public
  (event-privacy :control-action/requested) ;; => :confidential

  :leave-this-here)
