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

(def RefusalReason
  "Closed vocabulary for why an agent or phase refuses to proceed.

   One vocabulary shared by every refusal act on the stream — phase
   `:blocked` outcomes (PhaseCompleted) and meta-loop halts
   (MetaLoopHaltRequested) — so a refusal carries the same machine-readable
   cause wherever it originates, rather than only free text in `:message`.

   - :no-progress         — work stalled; no forward movement (progress monitor)
   - :quality-gate        — a quality threshold is unmet (e.g. test coverage)
   - :conflict            — an irreconcilable conflict was detected (e.g. merge)
   - :missing-input       — a required upstream artifact or spec is absent
   - :ambiguous-intent    — intent/spec underspecified; needs human resolution
   - :precondition-failed — a declared precondition or gate is not satisfied
   - :resource-unavailable— runtime, dependency, or executor is unavailable
   - :budget-exhausted    — the token or time budget is exhausted
   - :policy-block        — a policy pack forbids proceeding

   Closed: the schema validates against exactly this set, so a typo or a
   not-yet-added reason fails fast. Producers are all first-party, so catching
   those beats wire forward-compat. Extending it is a deliberate spec change."
  [:enum :no-progress :quality-gate :conflict :missing-input :ambiguous-intent
   :precondition-failed :resource-unavailable :budget-exhausted :policy-block])

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
  "Schema for workflow/started event.

   `:routing/trigger-event-id` (N5-delta-4 §4.3) is the bridge the
   automation-edge-correlator uses to map a handler workflow back to its
   originating routing trigger. Optional — workflows started outside the
   routing path (operator-initiated runs, etc.) omit the field, and the
   correlator's heuristic-fallback path (§3.5 case 2) covers the absence."
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
    ;; Plain `uuid?`, not `[:maybe uuid?]`: the producer-side contract
    ;; (`core/workflow-started`) explicitly omits this key when the value
    ;; is nil rather than emitting `{:routing/trigger-event-id nil}`. The
    ;; envelope is therefore EITHER a real uuid or the key is absent —
    ;; never a nil payload. Schema enforces that invariant.
    [:routing/trigger-event-id {:optional true} uuid?]
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
    ;; `:phase/outcome` is the typed-act surface for a phase boundary on the
    ;; observed layer (stream + evidence). The internal phase-result `:status`
    ;; stays a 2-valued control flag for the FSM; this enum carries the full act.
    ;; INFORM → :success/:failure/:skipped; REFUSE → :blocked (+ :phase/blocked-reason);
    ;; REQUEST(redirect) → :redirected (+ :phase/transition-request).
    [:phase/outcome {:optional true}
     [:enum :success :failure :skipped :blocked :redirected]]
    ;; Machine-readable cause, present when :phase/outcome is :blocked.
    [:phase/blocked-reason {:optional true} RefusalReason]
    ;; Review verdict, present only for the review phase. Carried so resume can
    ;; reconstruct a blocked review from events alone — the event is a writer of
    ;; this datum, one canonical location, no lossy reconstruction.
    [:phase/review-decision {:optional true} keyword?]
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

   Emitted when a workflow or operator-owned PR train creates a PR that
   should appear in the supervisory PR fleet. `:workflow/id` is the owning
   workflow run when one exists, and nil for operator-scoped train entries."
  (with-identity
   [:map
      [:event/type [:= :pr/created]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id [:maybe uuid?]]
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
    [:tool/names {:optional true} [:vector string?]]
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
   ;; Phase-heartbeat is a liveness signal carrying phase metrics — :internal
   ;; so it stays out of externally visible event logs.
   :workflow/phase-heartbeat   :internal
   :workspace/persisted :public
   :agent/started    :internal
   :agent/completed  :internal
   :agent/failed     :internal
   :agent/status     :internal
   :agent/chunk      :internal
   ;; Inter-agent messages carry routing metadata — :internal like other agent events.
   :agent/message-sent     :internal
   :agent/message-received :internal
   ;; Milestone sub-lifecycle events mirror :workflow/milestone-reached → :public.
   :phase/milestone-started   :public
   :phase/milestone-completed :public
   :phase/milestone-failed    :public
   :gate/started     :public
   :gate/passed      :public
   :gate/failed      :public
   ;; Per-rule policy evidence is finer-grained governance telemetry (one per
   ;; rule per phase) and may reference matched locations — :internal, not
   ;; :public, so it never reaches externally visible logs.
   :gate/rule-applied :internal
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
   ;; Meta-loop halt is a supervision signal — :internal, matching the
   ;; agent/supervisory families.
   :meta-loop/halt-requested :internal
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
   :zettel/promoted          :internal

   ;; Reliability metric events (RN-03). Operator-facing alerts — an SLO
   ;; breach and a degradation-mode transition — are `:public`; routine SLI
   ;; reads and error-budget recomputations are `:internal` telemetry.
   :reliability/sli-computed            :internal
   :reliability/slo-breach              :public
   :reliability/error-budget-update     :internal
   :reliability/degradation-mode-changed :public

   ;; Repository intelligence events (RN-19/20) — routine index-quality
   ;; telemetry, `:internal`.
   :repo-index/quality-measured :internal
   :repo-index/coverage-changed :internal})

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

;; Routing trigger event schemas (N5-delta-4 §4.2)
;;
;; Three new first-class trigger event types the automation-edge-correlator
;; classifies into RoutingTriggerKind values. Producer mapping per N5-δ4
;; §4.2:
;;
;;   :pr-monitor/review-comments-arrived  ← components/pr-lifecycle (PR webhook)
;;   :pr-monitor/ci-failed                ← components/pr-lifecycle (CI watcher)
;;   :standards-review/posted             ← components/standards-reviewer
;;                                          (deferred; emission site lands when
;;                                          the component exists)
;;
;; Schemas are added here so producers anywhere in the workspace can emit
;; well-formed events the correlator picks up via
;; `triggers/classify-trigger`. The correlator's heuristic-fallback path
;; (§3.5 case 2) covers absence at the producer side; the explicit-id path
;; (§3.5 case 1) needs N15-4's `:routing/trigger-event-id` on the handler
;; workflow's `:workflow/started`.

(def PrMonitorReviewCommentsArrived
  "Schema for `:pr-monitor/review-comments-arrived` event (N5-delta-4 §4.2.1).

   Emitted by the PR-watcher sub-modules in `components/pr-lifecycle`
   (the `pr-monitor` namespace cluster — there is no separate
   `components/pr-monitor` brick) when the GitHub webhook (or polling
   fallback) reports new review comments on a PR Miniforge owns.
   `:comments/agent-session-id`, when present, names the agent owning
   the PR per the PR↔agent index (AA-2).

   `:workflow/id` is `:optional`/`:maybe` and effectively nil on every
   real emission — `core/create-envelope` always stamps the key for
   envelope-shape uniformity, but routing-trigger events are PR-scoped
   not workflow-scoped, so the value carries no information. Schema
   models that shape rather than dropping the key entirely."
  (with-identity
   [:map
    [:event/type [:= :pr-monitor/review-comments-arrived]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:pr/repo string?]
    [:pr/number int?]
    [:comments/count int?]
    [:comments/agent-session-id {:optional true} [:maybe uuid?]]
    [:message string?]]))

(def PrMonitorCiFailed
  "Schema for `:pr-monitor/ci-failed` event (N5-delta-4 §4.2.2).

   Emitted by the PR-watcher sub-modules in `components/pr-lifecycle`
   (the `pr-monitor` namespace cluster) when a CI status transitions to
   a non-success terminal state. `:ci/conclusion` is an open keyword —
   known values: `:failure`, `:timed-out`, `:cancelled`. Consumers MUST
   tolerate additional values for forward compatibility.

   `:workflow/id` — see PrMonitorReviewCommentsArrived docstring."
  (with-identity
   [:map
    [:event/type [:= :pr-monitor/ci-failed]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:pr/repo string?]
    [:pr/number int?]
    [:ci/check-name string?]
    [:ci/conclusion keyword?]
    [:message string?]]))

(def StandardsReviewPosted
  "Schema for `:standards-review/posted` event (N5-delta-4 §4.2.3).

   Emitted by `components/standards-reviewer` (deferred; the component
   does not yet exist — the constructor lives in event-stream so any
   future producer can emit a well-formed event the correlator picks
   up). `:review/severity` is an open keyword — known values:
   `:advisory`, `:blocking`.

   `:workflow/id` — see PrMonitorReviewCommentsArrived docstring."
  (with-identity
   [:map
    [:event/type [:= :standards-review/posted]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:pr/repo string?]
    [:pr/number int?]
    [:affected/workflow-run-id {:optional true} [:maybe uuid?]]
    [:review/severity keyword?]
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

;------------------------------------------------------------------------------ Layer 5.5
;; Reliability metric event schemas (RN-03, N3 §3.17)
;;
;; Schemas for the 4 reliability metric events emitted by the SLI
;; computation engine (RN-04). Field names match the assoc'd keys in
;; the existing core.clj constructors (sli-computed, slo-breach,
;; error-budget-update, degradation-mode-changed).

(def SliComputed
  "Schema for :reliability/sli-computed event.

   Emitted by the SLI computation engine (RN-04) each time an SLI value
   is computed over a rolling window.  `:sli/value` is in the SLI's native
   units — a ratio in [0.0, 1.0] for rate-based SLIs (availability, success
   rate) or an absolute measure (e.g. latency ms) for others — so it is not
   range-constrained.  `:sli/tier` and `:sli/dimensions` are optional;
   single-tier deployments omit them."
  (with-identity
   [:map
    [:event/type [:= :reliability/sli-computed]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:sli/name keyword?]
    [:sli/value number?]
    [:sli/window keyword?]
    [:sli/tier {:optional true} keyword?]
    [:sli/dimensions {:optional true} map?]
    [:message string?]]))

(def SloBreach
  "Schema for :reliability/slo-breach event.

   Emitted when an SLO target is missed for :standard or :critical
   tiers.  All 5 required fields are always present — there is no
   partial-breach shape.  `:slo/target` and `:slo/actual` are in the SLI's
   native units (a ratio for rate-based SLIs, an absolute measure such as
   latency ms for others), so they are not range-constrained."
  (with-identity
   [:map
    [:event/type [:= :reliability/slo-breach]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:slo/sli-name keyword?]
    [:slo/target number?]
    [:slo/actual number?]
    [:slo/tier keyword?]
    [:slo/window keyword?]
    [:message string?]]))

(def ErrorBudgetUpdate
  "Schema for :reliability/error-budget-update event.

   Emitted when error-budget state is recomputed after each SLI window
   closes.  `:budget/remaining` is a ratio [0.0, 1.0]; `:budget/burn-rate`
   is a dimensionless multiplier (>1 = burning faster than replenished)."
  (with-identity
   [:map
    [:event/type [:= :reliability/error-budget-update]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:budget/tier keyword?]
    [:budget/sli keyword?]
    ;; ratio in [0.0, 1.0]; burn-rate is a non-negative multiplier
    ;; (>1 = burning faster than replenished, 0 = not burning)
    [:budget/remaining [:and number? [:>= 0] [:<= 1]]]
    [:budget/burn-rate [:and number? [:>= 0]]]
    [:budget/window keyword?]
    [:message string?]]))

(def DegradationModeChanged
  "Schema for :reliability/degradation-mode-changed event.

   Emitted when the system transitions between degradation modes per
   N1 §5.5.5.  `:degradation/trigger` is a human-readable string
   describing the condition that caused the transition."
  (with-identity
   [:map
    [:event/type [:= :reliability/degradation-mode-changed]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:degradation/from keyword?]
    [:degradation/to keyword?]
    [:degradation/trigger string?]
    [:message string?]]))

;------------------------------------------------------------------------------ Layer 6
;; Repository intelligence event schemas (RN-19/20)
;;
;; Schemas for the 2 repo-index events emitted by the index quality
;; tracker (RN-19/20).  `:index/id` is a string slug identifying the
;; index (e.g. "main-code-index"); quality and coverage are ratios
;; in [0.0, 1.0]; staleness is elapsed wall-clock milliseconds.

(def RepoIndexQualityMeasured
  "Schema for :repo-index/quality-measured event.

   Emitted by the index quality tracker (RN-19) each time it samples
   the composite quality score for a named index.  `:index/staleness-ms`
   is the age of the oldest document in the index at measurement time.
   `:index/measured-at` is optional (defaults to envelope timestamp
   when absent)."
  (with-identity
   [:map
    [:event/type [:= :repo-index/quality-measured]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:index/id string?]
    ;; quality and coverage are ratios in [0.0, 1.0]; staleness is a
    ;; non-negative elapsed-ms value
    [:index/quality-score [:and number? [:>= 0] [:<= 1]]]
    [:index/coverage [:and number? [:>= 0] [:<= 1]]]
    [:index/staleness-ms [:and int? [:>= 0]]]
    [:index/measured-at {:optional true} inst?]
    [:message string?]]))

(def RepoIndexCoverageChanged
  "Schema for :repo-index/coverage-changed event.

   Emitted by the index quality tracker (RN-20) when the coverage ratio
   of a named index changes beyond the configured threshold.
   `:index/changed-files` is the count of files whose index state
   changed in this transition; optional (omit when not computable)."
  (with-identity
   [:map
    [:event/type [:= :repo-index/coverage-changed]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:index/id string?]
    ;; coverage ratios in [0.0, 1.0]; changed-files is a non-negative count
    [:index/previous-coverage [:and number? [:>= 0] [:<= 1]]]
    [:index/coverage [:and number? [:>= 0] [:<= 1]]]
    [:index/changed-files {:optional true} [:and int? [:>= 0]]]
    [:message string?]]))

;; ----------------------------------------------------------------------------
;; Typed handoff acts
;;
;; Inter-agent messages and meta-loop halts are handoff points between agents
;; and phases. Each is an explicit, typed act on the stream rather than an
;; implicit signal reconstructed from status flags: INFORM (message) and REFUSE
;; (halt). Field values stay domain-native; the act lineage is documented, not
;; imported as a generic performative vocabulary. (The PR-monitor's classify→act
;; decision is the third such act, but the PR-monitor runs on its own decoupled
;; event bus — see `pr-lifecycle/monitor-events` `:pr-monitor/decision-recorded`
;; — so its typed act lives there, not on this stream.)

(def AgentMessageSent
  "Schema for `:agent/message-sent` event (N3 §3.7).

   Emitted by `core/inter-agent-message-sent` when one agent sends a message to
   another. `:message/type` is an open keyword — known values `:clarification-request`,
   `:clarification-response`, `:inform`, `:ack`; consumers MUST tolerate others."
  (with-identity
   [:map
    [:event/type [:= :agent/message-sent]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:from-agent/id keyword?]
    [:from-agent/instance-id {:optional true} uuid?]
    [:to-agent/id keyword?]
    [:message/type {:optional true} keyword?]
    [:message/content {:optional true} string?]
    [:message string?]]))

(def AgentMessageReceived
  "Schema for `:agent/message-received` event (N3 §3.7).

   Emitted by `core/inter-agent-message-received`. See AgentMessageSent for the
   `:message/type` vocabulary note."
  (with-identity
   [:map
    [:event/type [:= :agent/message-received]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id {:optional true} [:maybe uuid?]]
    [:from-agent/id keyword?]
    [:from-agent/instance-id {:optional true} uuid?]
    [:to-agent/id keyword?]
    [:message/type {:optional true} keyword?]
    [:message/content {:optional true} string?]
    [:message string?]]))

(def MetaLoopHaltRequested
  "Schema for `:meta-loop/halt-requested` event.

   The REFUSE act for the meta-loop: a meta-agent (progress monitor, test-quality,
   conflict detector) has signalled that the workflow must stop. Previously the
   halt lived only in the coordinator's return value and the runner's error map;
   this event makes the refusal first-class on the stream with a machine-readable
   cause (`:halt/reason-code`) alongside the halting agent and its free-text detail."
  (with-identity
   [:map
    [:event/type [:= :meta-loop/halt-requested]]
    [:event/id uuid?]
    [:event/timestamp inst?]
    [:event/version string?]
    [:event/sequence-number int?]
    [:workflow/id uuid?]
    [:workflow/phase {:optional true} keyword?]
    [:halt/halting-agent keyword?]
    [:halt/reason-code RefusalReason]
    [:halt/detail {:optional true} string?]
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
