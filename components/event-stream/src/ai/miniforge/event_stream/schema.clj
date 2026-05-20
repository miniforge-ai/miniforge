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

   See specs/normative/N3-event-stream.md for the full specification.

   Identity propagation (added for miniforge-fleet's Phase E.1
   planning prerequisite #10, Decision 14): every event schema
   carries an optional `:org/id` / `:workspace/id` / `:repo/id` /
   `:auth/context` quartet so subscribers can scope authorization
   without retrofitting the event log later. All four are optional
   for backward compatibility — single-org single-workspace
   deployments populate them with stable singleton values; multi-
   tenant deployments populate per-request. The `core/create-envelope`
   constructor accepts them via an optional options map (5-arg
   arity) and stamps them onto the envelope when present."
  (:require
   [ai.miniforge.event-stream.digest :as digest]))

;------------------------------------------------------------------------------ Layer 0
;; Shared payload schemas

(def TransitionRequest
  "Schema for a phase transition request payload."
  [:map
   [:transition/type keyword?]
   [:transition/target keyword?]])

;; ----------------------------------------------------------------------------
;; Decision-14 identity quartet (added for miniforge-fleet's Phase E.1
;; planning prerequisite #10). Defined ONCE here as a vector of Malli
;; `:map` entries; every event schema below uses `with-identity` to
;; append the same set. A single edit here propagates through every
;; event without missing one.

(def ^:private identity-entries
  "Four optional fields every event schema accepts so subscribers
   can scope authorization without retrofitting the wire format."
  [[:org/id       {:optional true} uuid?]
   [:workspace/id {:optional true} uuid?]
   [:repo/id      {:optional true} string?]
   [:auth/context {:optional true} map?]])

(defn with-identity
  "Pure: append the Decision-14 identity quartet to `base-schema`'s
   `:map` entries. Order in Malli `:map` is irrelevant for validation;
   the helper keeps the per-event schemas focused on their own
   payload while every event accepts the same identity set."
  [base-schema]
  (into base-schema identity-entries))

(def ^:private sha256-hex-pattern
  "Lowercase SHA-256 hexadecimal digest shape."
  (re-pattern (str "^[0-9a-f]{" digest/sha256-hex-length "}$")))

(def DigestSummary
  "Schema for bounded digest payloads attached to tool lifecycle events."
  [:map
   [:digest/preview string?]
   [:digest/sha256 [:re sha256-hex-pattern]]
   [:digest/original-size [:and int? [:>= 0]]]])

;------------------------------------------------------------------------------ Layer 0
;; Event envelope (base schema all events must conform to)

(def EventEnvelope
  "Base envelope schema for all events per N3 spec section 2."
  (with-identity
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
    [:message string?]]))               ; REQUIRED: human-readable message

;------------------------------------------------------------------------------ Layer 1
;; Workflow lifecycle event schemas

(def WorkflowStarted
  "Schema for workflow/started event."
  (with-identity
   [:map
      [:event/type [:= :workflow/started]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id uuid?]
    [:workflow/spec {:optional true} map?]
    [:workflow/intent {:optional true} map?]
    [:message string?]]))

(def PhaseStarted
  "Schema for workflow/phase-started event."
  (with-identity
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
    [:message string?]]))

(def PhaseCompleted
  "Schema for workflow/phase-completed event."
  (with-identity
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
    [:message string?]]))

(def WorkspacePersisted
  "Schema for workspace/persisted event.

   Emitted at phase boundaries when persist-workspace! has captured a
   checkpoint. Carries enough provenance for the dashboard / evidence
   bundle to surface 'inspect or resume from this archive'."
  (with-identity
   [:map
      [:event/type [:= :workspace/persisted]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id uuid?]
    [:workspace/phase {:optional true} keyword?]
    [:workspace/env-id {:optional true} string?]
    [:workspace/branch {:optional true} string?]
    [:workspace/commit-sha {:optional true} string?]
    [:workspace/bundle-path {:optional true} string?]
    [:workspace/tier {:optional true} [:enum :worktree :remote]]
    [:message string?]]))

(def WorkflowCompleted
  "Schema for workflow/completed event."
  (with-identity
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
    [:message string?]]))

(def WorkflowFailed
  "Schema for workflow/failed event."
  (with-identity
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
    [:message string?]]))

;------------------------------------------------------------------------------ Layer 1.4
;; Zettelkasten lifecycle event schemas (added for miniforge-fleet's
;; Phase E.3 outbox path — Fleet's ingest consumes these to grow the
;; cross-instance event log).

(def ^:private zettel-type-enum
  "Closed enum of zettel types accepted in the outbox event stream.

   MUST stay in sync with `ai.miniforge.knowledge.schema/ZettelType`
   — duplicated inline rather than required from `knowledge` to keep
   event-stream's dep boundary tight (the only consumer of this
   enum here is the cross-instance event shape Fleet ingests; the
   shared values are stable). When `knowledge.schema` adds a new
   type, mirror the addition here."
  [:enum :rule :concept :learning :example :hub :question :decision])

(def ZettelPromoted
  "Schema for zettel/promoted event.

   Emitted when a zettel revision transitions to `:trusted` state and
   is therefore eligible to ride the Fleet event log per Decision 6
   (trust on revision) + Decision 8 (privacy gates) of miniforge-
   fleet's Phase E plan. The event carries the revision-keyed identity
   triple plus the zettel's content + Fleet-share metadata so the
   ingest path can run its boundary validation without a separate
   round-trip into the local Zettelkasten store.

   The producer attaches `:fleet/oss-version` per Decision 13 so
   Fleet's E.4 quarantine + E.9 migration registry have the version
   pin they need without a second event-shape change later.

   Required fields beyond the envelope:
     :zettel/id          UUID
     :zettel/revision-id UUID
     :zettel/digest      lowercase 64-char hex
     :zettel/uid         human-readable id
     :zettel/title       short display name
     :zettel/content     markdown body
     :zettel/type        closed enum mirroring knowledge.schema/ZettelType
                          (`:rule` / `:concept` / `:learning` /
                           `:example` / `:hub` / `:question` /
                           `:decision`)
     :fleet/oss-version  version pin (per Decision 13)

   Optional Fleet-share intent (populated when the producer wants
   the zettel to actually ride the Fleet event log; absence means
   the promotion was local-only):
     :fleet/shareable        boolean
     :fleet/share-scope      :org / :team / :repo / :workflow
     :privacy/classification :public-org / :internal / :restricted /
                              :secret"
  [:map
   [:event/type [:= :zettel/promoted]]
   [:event/id uuid?]
   [:event/timestamp inst?]
   [:event/version string?]
   [:event/sequence-number int?]
   [:workflow/id uuid?]

   ;; Revision-keyed zettel identity (Decision 6).
   [:zettel/id          uuid?]
   [:zettel/revision-id uuid?]
   [:zettel/digest      [:re #"^[0-9a-f]{64}$"]]

   ;; Zettel content the Fleet ingest path validates against the
   ;; privacy gates (Decision 8). Carried alongside the triple so
   ;; subscribers don't need a round-trip to the producer's store.
   [:zettel/uid     [:string {:min 1}]]
   [:zettel/title   [:string {:min 1 :max 200}]]
   [:zettel/content [:string {:min 1}]]
   [:zettel/type    zettel-type-enum]

   ;; Version provenance (Decision 13).
   [:fleet/oss-version [:string {:min 1}]]

   ;; Optional Fleet-share intent (Decision 8).
   [:fleet/shareable        {:optional true} boolean?]
   [:fleet/share-scope      {:optional true}
    [:enum :org :team :repo :workflow]]
   [:privacy/classification {:optional true}
    [:enum :public-org :internal :restricted :secret]]

   ;; Identity propagation (Decision 14, added in event-stream side
   ;; via the matching PR for prerequisite #10).
   [:org/id       {:optional true} uuid?]
   [:workspace/id {:optional true} uuid?]
   [:repo/id      {:optional true} string?]
   [:auth/context {:optional true} map?]
   [:message string?]])

;------------------------------------------------------------------------------ Layer 1.5
;; PR lifecycle event schemas

(def PRCreated
  "Schema for pr/created event.

   Emitted when a workflow creates a PR that should appear in the
   supervisory PR fleet. `:workflow/id` is the owning workflow run, which
   lets downstream consumers correlate the PR back to the run/spec dossier."
  (with-identity
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
    [:message string?]]))

;------------------------------------------------------------------------------ Layer 2
;; Agent lifecycle event schemas

(def AgentStarted
  "Schema for agent/started event."
  (with-identity
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
    [:message string?]]))

(def AgentCompleted
  "Schema for agent/completed event."
  (with-identity
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
    [:message string?]]))

(def AgentChunk
  "Schema for agent/chunk event (streaming output)."
  (with-identity
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
    [:message string?]]))

(def AgentStatus
  "Schema for agent/status event (real-time progress)."
  (with-identity
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
    [:message string?]]))

;------------------------------------------------------------------------------ Layer 2.5
;; Tool-call lifecycle and phase heartbeat schemas (GROUP 1+2 foundation)

(def AgentToolCallStarted
  "Schema for agent/tool-call-started event.

   Emitted when an agent begins executing a single tool call.  Distinct
   from the legacy :agent/tool-call which records tool calls in aggregate;
   this event marks the precise start of execution for one call so
   latency and stuck-call detection are possible."
  (with-identity
   [:map
      [:event/type [:= :agent/tool-call-started]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id uuid?]
    [:tool/name {:optional true} string?]
    [:tool/args-digest {:optional true} DigestSummary]
    [:tool/call-id {:optional true} string?]
    [:agent/id keyword?]
    [:message string?]]))

(def ToolCallCompleted
  "Schema for tool/call-completed event.

   Emitted when a tool call finishes (success or failure).  Pairs with
   :agent/tool-call-started via :tool/call-id to close the latency span."
  (with-identity
   [:map
      [:event/type [:= :tool/call-completed]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id uuid?]
    [:tool/call-id {:optional true} string?]
    [:tool/result-digest {:optional true} DigestSummary]
    [:tool/duration-ms {:optional true} int?]
    [:tool/success? {:optional true} boolean?]
    [:tool/error {:optional true} map?]
    [:message string?]]))

(def PhaseHeartbeat
  "Schema for workflow/phase-heartbeat event.

   Emitted periodically by long-running phases so supervisors can
   detect stalls without requiring the phase to complete.  Carries
   the time elapsed since the phase became active and the gap since
   the last substantive event, enabling gap-based alerting."
  (with-identity
   [:map
      [:event/type [:= :workflow/phase-heartbeat]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id uuid?]
    [:phase/active-since inst?]
    [:phase/events-emitted int?]
    [:phase/last-event-at inst?]
    [:phase/gap-since-last-event-ms int?]
    [:message string?]]))

(def AgentStreamStalled
  "Schema for agent/stream-stalled event."
  (with-identity
   [:map
      [:event/type [:= :agent/stream-stalled]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id uuid?]
    [:workflow/phase keyword?]
    [:stream/gap-duration-ms int?]
    [:agent/backend keyword?]
    [:message string?]]))

(def AgentSessionCaptured
  "Schema for agent/session-captured event."
  (with-identity
   [:map
      [:event/type [:= :agent/session-captured]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id uuid?]
    [:workflow/phase keyword?]
    [:agent/backend keyword?]
    [:agent/session-id string?]
    [:message string?]]))

;------------------------------------------------------------------------------ Layer 3
;; Self-healing event schemas

(def WorkaroundApplied
  "Schema for self-healing/workaround-applied event."
  (with-identity
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
    [:message string?]]))

(def BackendSwitched
  "Schema for self-healing/backend-switched event."
  (with-identity
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
    [:message string?]]))

;------------------------------------------------------------------------------ Layer 4
;; LLM event schemas

(def LLMRequest
  "Schema for llm/request event."
  (with-identity
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
    [:message string?]]))

(def LLMResponse
  "Schema for llm/response event."
  (with-identity
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
    [:message string?]]))

;------------------------------------------------------------------------------ Layer 4.5
;; Dependency health event schemas

(def DependencyHealthUpdated
  "Schema for dependency/health-updated event."
  (with-identity
   [:map
      [:event/type [:= :dependency/health-updated]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:dependency/id keyword?]
    [:dependency/source keyword?]
    [:dependency/kind keyword?]
    [:dependency/status keyword?]
    [:dependency/failure-count int?]
    [:dependency/window-size int?]
    [:dependency/incident-counts map?]
    [:dependency/vendor {:optional true} keyword?]
    [:dependency/class {:optional true} keyword?]
    [:dependency/retryability {:optional true} keyword?]
    [:failure/class {:optional true} keyword?]
    [:dependency/previous-status {:optional true} keyword?]
    [:dependency/last-observed-at {:optional true} inst?]
    [:dependency/last-recovered-at {:optional true} inst?]
    [:message string?]]))

(def DependencyRecovered
  "Schema for dependency/recovered event."
  (with-identity
   [:map
      [:event/type [:= :dependency/recovered]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:dependency/id keyword?]
    [:dependency/source keyword?]
    [:dependency/kind keyword?]
    [:dependency/status [:= :healthy]]
    [:dependency/failure-count int?]
    [:dependency/window-size int?]
    [:dependency/incident-counts map?]
    [:dependency/vendor {:optional true} keyword?]
    [:dependency/class {:optional true} keyword?]
    [:dependency/retryability {:optional true} keyword?]
    [:failure/class {:optional true} keyword?]
    [:dependency/previous-status {:optional true} keyword?]
    [:dependency/last-observed-at {:optional true} inst?]
    [:dependency/last-recovered-at {:optional true} inst?]
    [:message string?]]))

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
   :workspace/persisted :public
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
   :dependency/health-updated :internal
   :dependency/recovered      :internal
   :supervision/tool-use-evaluated :internal
   :supervisory/intervention-requested :confidential
   :supervisory/intervention-state-changed :confidential
   ;; Automation-edge upserts mirror the same operator-attention shape as
   ;; the other supervisory snapshots; default `:internal` matches the
   ;; sibling `:supervisory/*-upserted` family.
   :supervisory/automation-edge-upserted :internal
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
   :chain/step-failed        :internal

   ;; Zettelkasten lifecycle. Default is `:internal` because the
   ;; event carries `:zettel/content` directly — Fleet's privacy
   ;; gates (Decision 8) decide whether the zettel is actually
   ;; cleared for cross-instance share, not the local default.
   :zettel/promoted          :internal})

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
  (with-identity
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
    [:message string?]]))

(def InterventionRequested
  "Schema for supervisory/intervention-requested event."
  (with-identity
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
    [:message string?]]))

(def InterventionStateChanged
  "Schema for supervisory/intervention-state-changed event."
  (with-identity
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
    [:message string?]]))

;; Automation-edge correlator emission (N5-delta-4 §4.1)
;;
;; The correlator is the sole producer of this event per N5-delta-1 §3.4
;; invariant 6 (extended in N5-delta-4 §4.1). Wire form mirrors the Rust
;; `AutomationEdge` struct in `miniforge-control/contracts/crates/
;; supervisory-entities/src/entities.rs`. The full entity malli schema lives
;; with the producer in `components/automation-edge-correlator/.../schema.clj`;
;; here we accept the entity as an open `map?` so that adding fields to the
;; producer's schema does not require a wire-schema edit in lockstep — the
;; consumer (Rust core) round-trips the open shape per §1.3.
(def AutomationEdgeUpserted
  "Schema for `:supervisory/automation-edge-upserted` event (N5-delta-4 §4.1).

   Carries the full AutomationEdge entity in `:supervisory/entity`. The
   correlator is the sole producer; consumers (Rust core, native app) dedup
   on the entity's `:edge/id` so re-emission on replay is safe."
  (with-identity
   [:map
    [:event/type [:= :supervisory/automation-edge-upserted]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    ;; `:workflow/id` is :optional/:maybe — its presence on the envelope
    ;; tracks the edge's correlation state, not the originating trigger
    ;; kind:
    ;;
    ;; - Pre-handler edges (`:observed` with no handler workflow
    ;;   correlated yet — the typical state after a `:pr/merged` trigger
    ;;   and before the responding workflow's `:workflow/started`) have
    ;;   no `:edge/handled-by-workflow-run-id` yet, so the producer
    ;;   threads `nil` and `create-envelope` omits the envelope field.
    ;; - Post-correlation edges (`:handled`, `:failed`, and the
    ;;   post-terminal `:suppressed` shape that preserved the prior
    ;;   workflow id) carry `:edge/handled-by-workflow-run-id` on the
    ;;   entity; the producer threads that value into `create-envelope`
    ;;   so the envelope's `:workflow/id` is populated for downstream
    ;;   workflow-scoped filters.
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:supervisory/entity map?]
    [:message string?]]))

;; OCI container event schemas

(def ContainerStarted
  "Schema for oci/container-started event."
  (with-identity
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
    [:message string?]]))

(def ContainerCompleted
  "Schema for oci/container-completed event."
  (with-identity
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
    [:message string?]]))

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
