<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N3 — Event Stream & Observability Contract

**Version:** 0.10.0-draft
**Date:** 2026-08-05
**Status:** Draft
**Conformance:** MUST

_v0.10.0 closes the spec's structural gaps: a canonical event-type registry
(§6), schema evolution and consumer compatibility rules (§7), sensitive-data
and redaction rules (§8), emission-failure semantics (§9), and traceable
conformance requirement IDs (§10.4)._

---

## 1. Purpose & Scope

This specification defines the **event stream contract** for miniforge workflows.
The event stream is a **product surface area**, not merely logging infrastructure.
It powers:

- Real-time UI updates (CLI/TUI/Web)
- Workflow replay and debugging
- Performance analytics
- Future learning and meta-loop systems
- Audit trails and compliance reporting

### 1.1 Design Principles

1. **Append-only** - Events are immutable once emitted
2. **Per-scope sequencing** - Events within a scope (§2.3) MUST be totally ordered
3. **Complete observability** - All agent actions, tool uses, and state transitions MUST emit events
4. **Machine-readable** - Events MUST be parseable and queryable
5. **Human-renderable** - Events MUST contain human-readable messages for UI display

---

## 2. Event Envelope

All events MUST conform to this base envelope:

```clojure
{:event/type keyword           ; REQUIRED: event type identifier
 :event/id uuid                ; REQUIRED: unique event ID
 :event/timestamp inst          ; REQUIRED: ISO-8601 timestamp
 :event/version string          ; REQUIRED: event schema version (e.g., "1.0.0")

 :workflow/id uuid              ; REQUIRED: workflow this event belongs to
 :workflow/phase keyword        ; OPTIONAL: current phase (:plan, :implement, etc.)

 :agent/id keyword              ; OPTIONAL: agent that emitted event
 :agent/instance-id uuid        ; OPTIONAL: specific agent instance

 :event/sequence-number long   ; REQUIRED: monotonic sequence within workflow
 :event/parent-id uuid          ; OPTIONAL: parent event ID (for causality)

 ;; Event-specific payload
 ...
}
```

### 2.1 Required Fields

- **event/type** - MUST be a namespaced keyword (e.g., `:agent/status`, `:workflow/started`)
- **event/id** - MUST be globally unique UUID
- **event/timestamp** - MUST be ISO-8601 instant
- **event/version** - MUST be semantic version string
- **workflow/id** - MUST reference a valid workflow. MAY be nil only for events whose scope is not Workflow; see §2.3.
- **event/sequence-number** - MUST be monotonically increasing within the event's scope (§2.3)

#### 2.1.1 Envelope Field Types

Envelope field types are fixed across every event family:

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `:event/type` | namespaced keyword | MUST | MUST be registered per §6 |
| `:event/id` | uuid | MUST | Globally unique |
| `:event/timestamp` | inst | MUST | ISO-8601, UTC |
| `:event/version` | string | MUST | Semantic version, see §7 |
| `:event/sequence-number` | long | MUST | Per-scope monotonic |
| `:event/parent-id` | uuid | MAY | Causality link |
| `:workflow/id` | uuid | MUST | Nilable only under §2.3 |
| `:workflow/phase` | keyword | MAY | Phase active at emission |
| `:agent/id` | keyword | MAY | Emitting agent archetype |
| `:agent/instance-id` | uuid | MAY | Emitting agent instance |
| `:pr/id` | uuid | Conditional | PR Work Item id — REQUIRED under §2.3 |
| `:pack/id` | string | Conditional | Pack scope key (§2.3) |
| `:repo/id` | string | Conditional | Repository scope key (§2.3) |
| `:deployment/id` | string | Conditional | Deployment scope key (§2.3) |
| `:supervisory/entity-key` | any | Conditional | Supervisory entity scope key (§2.3, §3.19.1) |
| `:message` | string | MUST | Human-renderable summary |

A _Conditional_ field is REQUIRED when it is the event's scope key per §2.3 and
OPTIONAL otherwise, where it serves only as a cross-reference.

Event families MUST NOT redefine an envelope field with a different type or
meaning. In particular:

- A family needing a provider-assigned PR number MUST use `:pr/number` (long).
  `:pr/id` is always the PR Work Item UUID.
- A family MUST NOT introduce a bare `:timestamp` key; `:event/timestamp` is the
  only event timestamp. Domain timestamps MUST be namespaced
  (e.g. `:pr/merged-at`).

### 2.2 Ordering Guarantees

Implementations MUST provide:

1. **Total order per scope** - Events within a scope (§2.3) MUST be sequenced
2. **Causal ordering** - If event B caused by event A, `sequence-number(B) > sequence-number(A)`
3. **Replay determinism** - Replaying a scope's events in sequence order MUST produce the same state

### 2.3 Scope Keys

Every event belongs to exactly one **scope**. The scope determines what
`:event/sequence-number` is monotonic with respect to (§2.2) and what a
consumer may subscribe to (§5.1). `:workflow/id` is the default scope, but
several families originate outside any workflow.

| Scope | Scope key | Families |
|-------|-----------|----------|
| Workflow | `:workflow/id` | Default for all of §3 unless listed below |
| PR Work Item | `:pr/id` | N9 external PR events (§3.16) |
| Pack | `:pack/id` | Pack install/update/remove (§3.12) |
| Repository | `:repo/id` | Repository intelligence (§3.18) |
| Supervisory entity | `:supervisory/entity-key` | Supervisory snapshots (§3.19) |
| Deployment | `:deployment/id` | Reliability metrics (§3.17) |

`:supervisory/entity-key` is the canonical ID of the entity the snapshot
describes, as listed per family in §3.19.1 — `:workflow-run/id`, `:agent/id`,
`[repo number]` for PRs, and so on. Sequencing per entity is what lets a
consumer detect a stale snapshot for one entity without serializing every
entity behind a single counter.

`:deployment/id` identifies the emitting deployment. Reliability metrics
describe the deployment rather than any workflow within it, and a fleet
aggregating several deployments onto one stream cannot order or attribute them
without it.

Rules:

- An event MUST carry a non-nil value for its scope's key.
- `:workflow/id` MAY be nil only for an event whose scope is not Workflow.
- `:event/sequence-number` MUST be monotonic within the scope. Deployment-scoped
  events are sequenced per emitting deployment.
- Implementations MUST support subscribing by each scope key in the table
  (§5.1), not only by `:workflow/id`.
- An event MAY carry keys from other scopes for cross-referencing. Doing so
  does not change its scope. In particular, for a Miniforge-originated PR,
  `:workflow/id` MUST reference the originating workflow and `:pr/id` MAY also
  be present; the event remains Workflow-scoped.
- A family that fits no row above MUST NOT be added to §3 until this table is
  amended. An event with no scope cannot be ordered, subscribed to, or replayed.

---

## 3. Required Event Types

Implementations MUST emit these event types.

**Reading the examples.** Every example below shows only the fields specific to
its event type plus whichever envelope fields aid comprehension. The full §2
envelope is REQUIRED on every event regardless of whether an example repeats it.
An example that omits `:event/id`, `:event/version`, or
`:event/sequence-number` is eliding them, not waiving them.

**Scope key.** Each family's scope is fixed by the table in §2.3 and restated
per family in the §6 registry. `:workflow/id` is the scope key unless that
table says otherwise.

### 3.1 Workflow Lifecycle Events

#### workflow/started

```clojure
{:event/type :workflow/started
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number 0

 :workflow/id uuid
 :workflow/spec {...}            ; Workflow specification
 :workflow/intent {...}          ; Intent declaration

 :message "Workflow started"}
```

#### workflow/phase-started

```clojure
{:event/type :workflow/phase-started
 :workflow/id uuid
 :workflow/phase :implement      ; Phase starting

 :phase/expected-agent :implementer
 :phase/context {...}            ; Context passed to agent

 :message "Implementation phase started"}
```

#### workflow/phase-completed

```clojure
{:event/type :workflow/phase-completed
 :workflow/id uuid
 :workflow/phase :implement

 :phase/duration-ms long
 :phase/outcome :success         ; :success :failure :skipped :blocked :redirected
 :phase/blocked-reason keyword   ; OPTIONAL: RefusalReason, present when :blocked
 :phase/artifacts [uuid ...]     ; Artifacts produced

 :message "Implementation phase completed"}
```

`:phase/outcome` is the typed act of a phase boundary on the observed layer.
`:success` / `:failure` / `:skipped` INFORM; `:blocked` is a REFUSE carrying a
machine-readable `:phase/blocked-reason` (a RefusalReason — see
`§3.7b meta-loop/halt-requested`); `:redirected` is a REQUEST to the pipeline,
detailed by `:phase/transition-request`. The internal phase-result `:status`
stays a two-valued control flag; this field carries the full act vocabulary.

#### workflow/completed

```clojure
{:event/type :workflow/completed
 :workflow/id uuid

 :workflow/status :success       ; :success, :failure, :cancelled
 :workflow/duration-ms long
 :workflow/evidence-bundle-id uuid

 :message "Workflow completed successfully"}
```

#### workflow/failed

```clojure
{:event/type :workflow/failed
 :workflow/id uuid

 :workflow/failure-phase :implement
 :workflow/failure-reason string       ; REQUIRED: human-readable description
 :failure/class keyword               ; REQUIRED: canonical class (see N1 §5.3.3)
 :workflow/error-details {...}

 :message "Workflow failed: {reason}"}
```

### 3.2 Agent Lifecycle Events

#### agent/started

```clojure
{:event/type :agent/started
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid
 :workflow/phase :implement

 :agent/context {...}            ; Agent received context

 :message "Implementer agent started"}
```

#### agent/completed

```clojure
{:event/type :agent/completed
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid

 :agent/duration-ms long
 :agent/outcome :success
 :agent/output {...}
 :agent/artifacts [uuid ...]

 :message "Implementer agent completed"}
```

#### agent/failed

```clojure
{:event/type :agent/failed
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid

 :agent/failure-reason string         ; REQUIRED: human-readable description
 :failure/class keyword               ; REQUIRED: canonical class (see N1 §5.3.3)
 :agent/error-details {...}
 :agent/retry-count long

 :message "Implementer agent failed: {reason}"}
```

### 3.3 Agent Status Events (Real-Time Progress)

#### agent/status

```clojure
{:event/type :agent/status
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid
 :workflow/phase :implement

 :status/type :generating        ; See 3.3.1
 :status/detail string            ; Specific activity description
 :status/progress-percent long   ; OPTIONAL: 0-100

 :message "Generating Terraform import blocks..."}
```

##### 3.3.1 Status Types

Implementations MUST support these status types:

- `:reading` - Reading files, specs, context
- `:thinking` - Analyzing, planning, reasoning
- `:generating` - Creating code, artifacts
- `:validating` - Running policy checks, tests
- `:repairing` - Fixing issues (inner loop)
- `:running` - Executing external commands
- `:waiting` - Waiting for dependency, approval
- `:communicating` - Talking to other agents

### 3.4 Subagent Events

#### subagent/spawned

```clojure
{:event/type :subagent/spawned
 :parent-agent/id :implementer
 :parent-agent/instance-id uuid
 :subagent/id :terraform-planner
 :subagent/instance-id uuid
 :workflow/id uuid

 :subagent/purpose string

 :message "Spawned subagent: terraform-planner"}
```

All subagent events MUST include `parent-agent/id` and `parent-agent/instance-id`.

### 3.5 Tool Use Events

#### tool/invoked

```clojure
{:event/type :tool/invoked
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid

 :tool/name :read-file
 :tool/args {:file-path "terraform/main.tf"}

 :message "Reading file: terraform/main.tf"}
```

#### tool/completed

```clojure
{:event/type :tool/completed
 :agent/id :implementer
 :agent/instance-id uuid
 :workflow/id uuid

 :tool/name :read-file
 :tool/duration-ms long
 :tool/result {...}              ; OPTIONAL: result summary

 :message "File read complete: terraform/main.tf"}
```

### 3.6 LLM Call Events

#### llm/request

```clojure
{:event/type :llm/request
 :agent/id :planner
 :agent/instance-id uuid
 :workflow/id uuid

 :llm/model "claude-sonnet-4"
 :llm/prompt-tokens long
 :llm/request-id uuid

 :message "Calling Claude Sonnet (2.4k tokens)..."}
```

#### llm/response

```clojure
{:event/type :llm/response
 :agent/id :planner
 :agent/instance-id uuid
 :workflow/id uuid

 :llm/model "claude-sonnet-4"
 :llm/request-id uuid            ; Links to request
 :llm/completion-tokens long
 :llm/total-tokens long
 :llm/duration-ms long
 :llm/cost-usd decimal           ; OPTIONAL

 :message "Received response (850 tokens, 3.2s)"}
```

### 3.7 Inter-Agent Communication Events

#### agent/message-sent

```clojure
{:event/type :agent/message-sent
 :from-agent/id :implementer
 :from-agent/instance-id uuid           ; OPTIONAL
 :to-agent/id :planner
 :workflow/id uuid

 :message/type :clarification-request   ; OPTIONAL; open keyword, consumers tolerate unknowns
 :message/content string                ; OPTIONAL

 :message "Asking Planner: Should we create new security group?"}
```

#### agent/message-received

```clojure
{:event/type :agent/message-received
 :from-agent/id :planner
 :to-agent/id :implementer
 :workflow/id uuid

 :message/type :clarification-response   ; OPTIONAL; open keyword, consumers tolerate unknowns
 :message/content string                 ; OPTIONAL

 :message "Planner response: Reuse existing security group sg-prod-rds"}
```

### 3.7b Meta-Loop Halt

The REFUSE act for meta-supervision. A meta-agent (progress monitor,
test-quality, conflict detector) can stop the workflow; this event makes that
refusal first-class on the stream with a machine-readable cause, rather than
leaving it only in the coordinator's return value and the runner's error map.

`:halt/reason-code` is a **RefusalReason** — the closed vocabulary shared with
`:phase/blocked-reason`:

`:no-progress :quality-gate :conflict :missing-input :ambiguous-intent
:precondition-failed :resource-unavailable :budget-exhausted :policy-block`

The vocabulary is closed: the schema validates `:halt/reason-code` against exactly
this set. Adding a reason is a deliberate spec change, not an open extension point.

#### meta-loop/halt-requested

```clojure
{:event/type :meta-loop/halt-requested
 :workflow/id uuid
 :workflow/phase :implement              ; OPTIONAL: phase active at halt

 :halt/halting-agent :conflict-detector  ; meta-agent that refused
 :halt/reason-code :conflict             ; RefusalReason
 :halt/detail string                     ; OPTIONAL: free-text from the agent

 :message "Meta-loop halt requested by conflict-detector: conflict"}
```

### 3.8 Milestone Events

#### milestone/reached

```clojure
{:event/type :milestone/reached
 :agent/id :implementer
 :workflow/id uuid
 :workflow/phase :implement

 :milestone/id :code-generated
 :milestone/description string
 :milestone/artifacts [uuid ...]

 :message "Code generation complete"}
```

Implementations SHOULD emit milestone events for:

- Phase boundaries
- Gate transitions
- Major artifact generation
- Validation pass/fail

### 3.9 Gate Events

#### gate/started

```clojure
{:event/type :gate/started
 :workflow/id uuid
 :workflow/phase :implement

 :gate/id keyword                ; e.g., :policy-validation
 :gate/type :policy              ; :policy, :test, :lint, :security
 :gate/artifacts [uuid ...]      ; Artifacts being gated

 :message "Starting policy validation gate"}
```

#### gate/passed

```clojure
{:event/type :gate/passed
 :workflow/id uuid
 :workflow/phase :implement

 :gate/id keyword
 :gate/duration-ms long
 :gate/results {...}

 :message "Policy validation gate passed"}
```

#### gate/failed

```clojure
{:event/type :gate/failed
 :workflow/id uuid
 :workflow/phase :implement

 :gate/id keyword
 :gate/violations [...]          ; Violation details
 :gate/remediation {...}         ; Suggested fixes

 :message "Policy validation gate failed: 3 violations"}
```

---

### 3.10 PR Lifecycle Events (DAG Orchestration)

For DAG-based multi-task execution (see N2 Section 13), implementations MUST emit
PR lifecycle events. These events correlate DAG execution, task workflow, and PR
integration states.

#### Common Correlation Fields

All PR lifecycle events MUST include these correlation fields, in addition to
the §2 envelope:

```clojure
{:dag/id uuid                    ; REQUIRED: DAG run ID
 :run/id uuid                    ; REQUIRED: Run instance ID
 :plan/id uuid                   ; OPTIONAL: Plan ID (if applicable)
 :task/id uuid                   ; REQUIRED: Task workflow ID
 :pr/id uuid                     ; REQUIRED: PR Work Item id (§2.1.1)
 :pr/number long                 ; REQUIRED: Provider-assigned PR number
 :pr/repo string                 ; REQUIRED: "org/name"
 :pr/url string                  ; OPTIONAL: Full PR URL
 :sha string}                    ; REQUIRED: Commit SHA
```

`:pr/id` is the PR Work Item UUID defined in §2.3 and used identically by the
N9 family (§3.16), so a Miniforge-originated PR and its external provider
events correlate on one key. The provider-assigned number is `:pr/number`.
Event time is carried by the envelope's `:event/timestamp`; these events MUST
NOT carry a bare `:timestamp` key.

These events are Workflow-scoped (§2.3): the envelope's `:workflow/id` is the
workflow executing the task, and `:task/id` identifies the DAG node it
executes. The two are distinct identifiers and both are REQUIRED.

#### pr/opened

```clojure
{:event/type :pr/opened
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id uuid
 :pr/number long
 :pr/url string
 :pr/branch string
 :pr/base-sha string
 :sha string

 :message "PR #123 opened for task"}
```

#### pr/ci-passed

```clojure
{:event/type :pr/ci-passed
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id uuid
 :pr/number long
 :sha string
 :ci/checks [{:name string :status :success :duration-ms long}]

 :message "CI passed for PR #123"}
```

#### pr/ci-failed

```clojure
{:event/type :pr/ci-failed
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id uuid
 :pr/number long
 :sha string
 :ci/checks [{:name string :status :failure :output string}]
 :ci/failure-summary string

 :message "CI failed for PR #123: 2 tests failing"}
```

#### pr/review-approved

```clojure
{:event/type :pr/review-approved
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id uuid
 :pr/number long
 :sha string
 :review/approvers [string ...]
 :review/approval-count long

 :message "PR #123 approved by alice, bob"}
```

#### pr/review-changes-requested

```clojure
{:event/type :pr/review-changes-requested
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id uuid
 :pr/number long
 :sha string
 :review/requesters [string ...]
 :review/comments [{:file string :line long :body string}]

 :message "Changes requested on PR #123"}
```

#### pr/comment-actionable

```clojure
{:event/type :pr/comment-actionable
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id uuid
 :pr/number long
 :sha string
 :comment/id string
 :comment/author string
 :comment/body string
 :comment/file string            ; OPTIONAL: if inline comment
 :comment/line long              ; OPTIONAL: if inline comment
 :comment/classification keyword ; :code-change, :bug-report, :test-failure, :constraint-violation

 :message "Actionable comment on PR #123: fix null check"}
```

#### pr/fix-pushed

```clojure
{:event/type :pr/fix-pushed
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id uuid
 :pr/number long
 :sha string                     ; New commit SHA
 :fix/type keyword               ; :ci-failure, :review-changes, :conflict
 :fix/iteration long             ; Fix attempt number
 :fix/files-modified [string ...]

 :message "Fix pushed for PR #123 (attempt 2)"}
```

#### pr/merged

```clojure
{:event/type :pr/merged
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id uuid
 :pr/number long
 :sha string                     ; Merge commit SHA
 :pr/merge-method keyword        ; :merge, :squash, :rebase

 :message "PR #123 merged"}
```

#### pr/closed

```clojure
{:event/type :pr/closed
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id uuid
 :pr/number long
 :sha string
 :close/reason keyword           ; :abandoned, :superseded, :failed

 :message "PR #123 closed: max fix iterations exceeded"}
```

---

### 3.11 ETL and Pack Events

ETL workflows and pack promotion MUST emit the following additional events.

#### etl/started

```clojure
{:event/type :etl/started
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :workflow/id uuid
 :etl/source {:repo/path string}
 :etl/output {:pack-root string :report-root string}

 :message "ETL started"}
```

#### etl/sources-classified

```clojure
{:event/type :etl/sources-classified
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :workflow/id uuid
 :etl/classification-summary
 {:candidates long
  :feature-sources long
  :policy-sources long}

 :message "ETL classification complete"}
```

#### etl/safety-scan-completed

```clojure
{:event/type :etl/safety-scan-completed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :workflow/id uuid
 :etl/risk-summary
 {:high long :medium long :low long}

 :message "ETL safety scan completed"}
```

#### pack/generated

```clojure
{:event/type :pack/generated
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :workflow/id uuid
 :pack/id string
 :pack/type keyword                    ; :feature-pack | :policy-pack | :agent-profile-pack | :pack-index
 :pack/content-hash string
 :pack/trust-level keyword             ; :untrusted by default

 :message "Pack generated"}
```

#### pack/promoted

```clojure
{:event/type :pack/promoted
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :pack/id string
 :pack/type keyword
 :from-trust keyword
 :to-trust keyword
 :promotion/policy-pack string
 :pack/content-hash string
 :pack/signature string                ; OPTIONAL

 :message "Pack promoted"}
```

#### etl/completed

```clojure
{:event/type :etl/completed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :workflow/id uuid
 :etl/duration-ms long
 :etl/summary
 {:packs-generated long
  :packs-promoted long
  :high-risk-findings long
  :sources-processed long}

 :message "ETL workflow completed successfully"}
```

ETL workflows MUST emit this event after all pack generation and promotion activities complete.

#### etl/failed

```clojure
{:event/type :etl/failed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :workflow/id uuid
 :etl/failure-stage keyword           ; :classification | :scanning | :extraction | :validation
 :etl/failure-reason string           ; REQUIRED: human-readable description
 :failure/class keyword               ; REQUIRED: canonical class (see N1 §5.3.3)
 :etl/error-details {...}             ; OPTIONAL: structured error information

 :message "ETL workflow failed: {reason}"}
```

ETL workflows MUST emit this event if any critical failure prevents completion.
Implementations SHOULD include enough detail in `:etl/error-details` to enable
debugging without log diving.

### 3.12 Pack Lifecycle and Pack Run Events

For Workflow Pack management and execution (see N1 §2.24–§2.26), implementations MUST emit
pack lifecycle events and Pack Run events.

#### pack/installed

```clojure
{:event/type :pack/installed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :pack/id string
 :pack/version string
 :pack/type keyword                    ; :workflow-pack | :policy-pack | etc.
 :pack/publisher string
 :pack/content-hash string
 :pack/signature-verified? boolean
 :pack/capabilities-required [{:capability/id string :capability/scope keyword}]
 :pack/capabilities-granted [{:capability/id string :capability/scope keyword}]

 :message "Pack installed: {pack.id}@{pack.version}"}
```

#### pack/updated

```clojure
{:event/type :pack/updated
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :pack/id string
 :pack/from-version string
 :pack/to-version string
 :pack/content-hash string
 :pack/capabilities-changed? boolean   ; true if capabilities differ from prior version
 :pack/re-approval-required? boolean   ; true if capability upgrade requires re-approval

 :message "Pack updated: {pack.id} {from-version} → {to-version}"}
```

#### pack/removed

```clojure
{:event/type :pack/removed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :pack/id string
 :pack/version string
 :pack/content-hash string

 :message "Pack removed: {pack.id}@{pack.version}"}
```

#### pack.run/started

```clojure
{:event/type :pack.run/started
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :workflow/id uuid                     ; workflow created for this run
 :pack-run/id uuid
 :pack/id string
 :pack/version string
 :pack/content-hash string
 :pack/entrypoint string
 :pack/signature-verified? boolean
 :pack/capabilities-granted [{:capability/id string :capability/scope keyword}]

 :message "Pack run started: {pack.id}@{pack.version} / {entrypoint}"}
```

#### pack.run/completed

```clojure
{:event/type :pack.run/completed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :workflow/id uuid
 :pack-run/id uuid
 :pack/id string
 :pack/version string
 :pack-run/duration-ms long
 :pack-run/evidence-bundle-id uuid

 :message "Pack run completed: {pack.id}@{pack.version}"}
```

#### pack.run/failed

```clojure
{:event/type :pack.run/failed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :workflow/id uuid
 :pack-run/id uuid
 :pack/id string
 :pack/version string
 :pack-run/failure-reason string       ; REQUIRED: human-readable description
 :failure/class keyword               ; REQUIRED: canonical class (see N1 §5.3.3)
 :pack-run/duration-ms long

 :message "Pack run failed: {pack.id}@{pack.version} — {failure-reason}"}
```

#### capability/denied

```clojure
{:event/type :capability/denied
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :workflow/id uuid
 :pack-run/id uuid
 :pack/id string
 :capability/attempted string          ; e.g., "github.pr.comment.write"
 :capability/granted-set [string ...]  ; capabilities that were granted

 :message "Capability denied: {capability.attempted} not in grant set for {pack.id}"}
```

#### chain.edge/started

```clojure
{:event/type :chain.edge/started
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :chain/id uuid
 :edge/id uuid
 :edge/from-workflow-id uuid
 :edge/to-workflow-id uuid
 :edge/bindings-count long

 :message "Chain edge started: {from-workflow} → {to-workflow}"}
```

#### chain.edge/completed

```clojure
{:event/type :chain.edge/completed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :chain/id uuid
 :edge/id uuid
 :edge/from-workflow-id uuid
 :edge/to-workflow-id uuid
 :edge/duration-ms long

 :message "Chain edge completed: {from-workflow} → {to-workflow}"}
```

#### chain.edge/failed

```clojure
{:event/type :chain.edge/failed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :chain/id uuid
 :edge/id uuid
 :edge/from-workflow-id uuid
 :edge/to-workflow-id uuid
 :edge/failure-reason string          ; REQUIRED: human-readable description
 :failure/class keyword               ; REQUIRED: canonical class (see N1 §5.3.3)

 :message "Chain edge failed: {from-workflow} → {to-workflow} — {failure-reason}"}
```

### 3.13 Task Lifecycle Events (DAG Orchestration)

For DAG-based multi-task execution (see N2 Section 13), implementations MUST emit
task lifecycle events that track frontier computation, agent dispatch, and capability
binding. These events enable Kanban projections and capability audit trails.

#### Common Correlation Fields

All task lifecycle events MUST include these correlation fields, in addition to
the §2 envelope:

```clojure
{:dag/id uuid                    ; REQUIRED: DAG run ID
 :run/id uuid                    ; REQUIRED: Run instance ID
 :task/id uuid}                  ; REQUIRED: Task ID
```

Event time is carried by the envelope's `:event/timestamp`; these events MUST
NOT carry a bare `:timestamp` key. As in §3.10, the envelope's `:workflow/id`
is the scope key and `:task/id` identifies the DAG node.

#### task/frontier-entered

Emitted when a task's dependencies are all satisfied and it becomes eligible for dispatch.

```clojure
{:event/type :task/frontier-entered
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :frontier/size long             ; Current frontier size after this task entered
 :frontier/trigger-task uuid     ; Task whose terminal state caused this entry

 :message "Task entered frontier (frontier size: 3)"}
```

#### task/claimed

Emitted when a task is dispatched to an agent for execution.

```clojure
{:event/type :task/claimed
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :agent/archetype keyword        ; e.g., :implementer
 :agent/instance-id uuid         ; Unique agent instance
 :claim/lease-ms long            ; OPTIONAL: Lease duration if time-boxed

 :message "Task claimed by implementer agent"}
```

#### task/capability-bound

Emitted when an agent is scoped to a task's capability contract.

```clojure
{:event/type :task/capability-bound
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :agent/instance-id uuid
 :cap/tools [keyword ...]        ; Tools granted
 :cap/paths [string ...]         ; File paths granted
 :cap/knowledge [string ...]     ; Knowledge packs granted
 :cap/source keyword             ; :task-contract, :archetype-default, :dag-default

 :message "Agent capabilities bound: 4 tools, 2 path patterns"}
```

#### task/scope-violation

Emitted when an agent attempts an operation outside its capability contract.
This is a WARN-level event; the operation MUST be blocked.

```clojure
{:event/type :task/scope-violation
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :agent/instance-id uuid
 :violation/type keyword          ; :tool-denied, :path-denied, :knowledge-denied
 :violation/requested string      ; What was requested (e.g., tool name, file path)
 :violation/allowed [string ...]  ; What was allowed

 :message "Scope violation: agent requested :delete-branch but allowed tools are [:write-file :run-cmd]"}
```

#### task/skip-propagated

Emitted when a task is skipped due to a dependency failure.

```clojure
{:event/type :task/skip-propagated
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :skip/cause-task uuid            ; The task whose failure triggered the skip
 :skip/cause-chain [uuid ...]     ; Full chain from root failure to this task

 :message "Task skipped: dependency task-abc failed"}
```

### 3.14 OPSV Events (N7)

For Operational Policy Synthesis workflows (see N7), implementations MUST emit these
event types:

#### opsv.experiment/planned

```clojure
{:event/type :opsv.experiment/planned
 :workflow/id uuid
 :opsv/evidence-bundle-id uuid
 :opsv/experiment-pack-hash string   ; Experiment Pack content hash
 :opsv/targets {:services [...] :environments [...]}
 :opsv/risk-score
 {:score double                      ; [0.0, 1.0]
  :level keyword                     ; :low, :medium, :high, :critical
  :factors [{:factor keyword
             :input any
             :contribution double
             :rationale string}]}
 :message "OPSV experiment planned: {experiment-pack-hash}"}
```

#### opsv.experiment/started

```clojure
{:event/type :opsv.experiment/started
 :workflow/id uuid
 :opsv/evidence-bundle-id uuid
 :opsv/experiment-pack-hash string
 :opsv/environment-fingerprint
 {:cluster string
  :node-pools [string ...]
  :image-digests {...}
  :config-hash string}
 :message "OPSV experiment started in {environment-fingerprint}"}
```

#### opsv/load-step

```clojure
{:event/type :opsv/load-step
 :workflow/id uuid
 :opsv/evidence-bundle-id uuid
 :opsv/step-id string
 :opsv/intended-load {...}
 :opsv/observed-load {...}
 :message "OPSV load step {step-id}: {intended-load} → {observed-load}"}
```

#### opsv.guardrail/abort

```clojure
{:event/type :opsv.guardrail/abort
 :workflow/id uuid
 :opsv/evidence-bundle-id uuid
 :opsv/trigger keyword               ; Abort trigger type
 :opsv/threshold {...}
 :opsv/observed {...}
 :opsv/rollback-action keyword
 :message "OPSV guardrail abort: {trigger}"}
```

#### opsv.convergence/iteration

```clojure
{:event/type :opsv.convergence/iteration
 :workflow/id uuid
 :opsv/evidence-bundle-id uuid
 :opsv/iteration-id string
 :opsv/params {...}
 :opsv/observed-metrics-summary {...}
 :message "OPSV convergence iteration {iteration-id}"}
```

#### opsv.policy/proposed

```clojure
{:event/type :opsv.policy/proposed
 :workflow/id uuid
 :opsv/evidence-bundle-id uuid
 :opsv/policy-hash string
 :opsv/diff-artifact-refs [uuid ...] ; N6 artifact references
 :opsv/confidence keyword
 :message "OPSV policy proposed: {policy-hash}"}
```

#### opsv.verification/result

```clojure
{:event/type :opsv.verification/result
 :workflow/id uuid
 :opsv/evidence-bundle-id uuid
 :opsv/passed? boolean
 :opsv/criteria-evaluation
 [{:criterion/id string
   :criterion/passed? boolean
   :criterion/observed any
   :criterion/expected any
   :criterion/reason-code keyword}]
 :opsv/confidence keyword
 :opsv/caveats [string ...]
 :message "OPSV verification result: {passed?}"}
```

#### opsv.actuation/emitted

```clojure
{:event/type :opsv.actuation/emitted
 :workflow/id uuid
 :opsv/evidence-bundle-id uuid
 :opsv/requested-actuation-mode keyword ; :recommend-only, :pr-only, :apply-allowed
 :opsv/effective-actuation-mode keyword ; :none, :recommend-only, :pr-only, :apply-allowed
 :opsv/governed-effects              ; Correlates each N10 intent, OIR, and capability
 [{:evidence/intent-id uuid
   :evidence/oir-id uuid
   :evidence/capability-id string}]
 :opsv/pr-refs [string ...]          ; PR URLs if PR_ONLY
 :opsv/apply-refs [string ...]       ; Applied resource refs if APPLY_ALLOWED
 :message "OPSV actuation emitted: {effective-actuation-mode}"}
```

#### opsv.drift/detected

```clojure
{:event/type :opsv.drift/detected
 :workflow/id uuid                   ; Originating OPSV workflow for the monitored policy
 :opsv/evidence-bundle-id uuid
 :opsv/signal keyword
 :opsv/deviation {...}
 :opsv/suggested-rerun? boolean
 :message "OPSV drift detected: {signal}"}
```

All OPSV events MUST include `:opsv/evidence-bundle-id`. The OPSV workflow
MUST allocate that identifier before `:opsv.experiment/planned` and finalize
the corresponding bundle per N6. Drift events reference the originating
workflow and bundle for the monitored policy.

### 3.15 Observability Control Interface Events (N8)

For the Observability Control Interface (see N8), implementations MUST emit these
event types:

#### listener/attached

```clojure
{:event/type :listener/attached
 :listener/id uuid
 :listener/type keyword              ; :watcher, :dashboard, :fleet, :enterprise
 :listener/capability keyword        ; :observe, :advise, :control
 :workflow/id uuid
 :message "Listener attached: {type} with {capability} capability"}
```

#### listener/detached

```clojure
{:event/type :listener/detached
 :listener/id uuid
 :workflow/id uuid
 :listener/reason keyword            ; :disconnect, :timeout, :revoked
 :message "Listener detached: {reason}"}
```

#### listener/overflow

Emitted when a listener falls behind the server's send buffer and events are
dropped from its delivery (§5.3.6). The event reports a delivery loss to that
listener; it does not imply the events were lost from storage (§9.4).

```clojure
{:event/type :listener/overflow
 :listener/id uuid
 :workflow/id uuid
 :overflow/dropped-count long        ; REQUIRED: events dropped for this listener
 :overflow/oldest-dropped-sequence long ; REQUIRED: first sequence number dropped
 :overflow/newest-dropped-sequence long ; REQUIRED: last sequence number dropped
 :message "Listener overflow: {dropped-count} events dropped"}
```

The sequence range lets a listener choosing to recover reconnect with
`?from-sequence=` (§5.3.5) at a known-good position rather than guessing.

#### control-action/requested

```clojure
{:event/type :control-action/requested
 :action/id uuid
 :action/type keyword                ; See N8 §3.1
 :action/target {:target-type keyword :target-id uuid}
 :action/requester {:principal string :listener-id uuid}
 :workflow/id uuid
 :message "Control action requested: {type}"}
```

#### control-action/executed

```clojure
{:event/type :control-action/executed
 :action/id uuid
 :action/type keyword
 :action/result {:status keyword :error {...}}
 :workflow/id uuid
 :message "Control action executed: {type} - {status}"}
```

#### control-action/approval-required

```clojure
{:event/type :control-action/approval-required
 :action/id uuid
 :action/type keyword
 :approval/required-approvers int
 :approval/timeout-at inst
 :workflow/id uuid
 :message "Approval required for {type}: {required} approvers needed"}
```

#### annotation/created

```clojure
{:event/type :annotation/created
 :workflow/id uuid
 :annotation/id uuid
 :annotation/type keyword            ; :recommendation, :warning, :insight, :question
 :annotation/source {:listener-id uuid :principal string}
 :message "Advisory annotation: {title}"}
```

### 3.16 External PR Integration Events (N9)

For external PR integration (see N9), implementations MUST emit these event types.
These events MAY have nil `:workflow/id` — see §2.3 for scope key rules.

#### provider/event-received

Emitted when a provider event is received and normalized.

```clojure
{:event/type :provider/event-received
 :pr/id uuid                         ; PR Work Item id (correlation key)
 :provider/type keyword              ; :github, :gitlab
 :provider/event-type keyword        ; Canonical type mapped from provider
 :provider/repo string               ; "org/name"
 :provider/pr-number long            ; OPTIONAL
 :provider/head-sha string           ; OPTIONAL
 :provider/dedupe-key string         ; For idempotency
 :message "Provider event received: {type} for {repo}#{pr-number}"}
```

#### pr.readiness/changed

Emitted when PR readiness state changes (derived-state-change event).

```clojure
{:event/type :pr.readiness/changed
 :pr/id uuid
 :pr/repo string
 :pr/number long
 :readiness/previous-state keyword
 :readiness/new-state keyword
 :readiness/blockers [...]
 :message "PR {repo}#{number} readiness: {previous} → {new}"}
```

#### pr.risk/changed

```clojure
{:event/type :pr.risk/changed
 :pr/id uuid
 :pr/repo string
 :pr/number long
 :risk/previous-level keyword
 :risk/new-level keyword
 :risk/factors [...]
 :risk/evidence-id uuid              ; N6 artifact id
 :message "PR {repo}#{number} risk: {previous} → {new}"}
```

#### pr.policy/changed

```clojure
{:event/type :pr.policy/changed
 :pr/id uuid
 :pr/repo string
 :pr/number long
 :policy/previous-overall keyword
 :policy/new-overall keyword
 :policy/results [...]
 :policy/evidence-id uuid            ; N6 artifact id
 :message "PR {repo}#{number} policy: {previous} → {new}"}
```

#### pr.state/changed

```clojure
{:event/type :pr.state/changed
 :pr/id uuid
 :pr/repo string
 :pr/number long
 :pr/previous-state keyword          ; :open, :closed, :merged
 :pr/new-state keyword
 :pr/head-sha string
 :message "PR {repo}#{number} state: {previous} → {new}"}
```

#### train/changed

```clojure
{:event/type :train/changed
 :train/id uuid
 :train/members [uuid ...]           ; Ordered PR Work Item ids
 :train/change-type keyword          ; :member-added, :member-removed,
                                     ; :order-changed, :member-merged
 :message "Train {id}: {change-type}"}
```

#### N9 Event Ordering Rules

- Provider ingestion events MUST be idempotent per `:provider/dedupe-key`.
- Derived-state-change events (`:pr.readiness/changed`, etc.) MUST only fire when
  computed state actually changes.
- All events MUST conform to §2.2 ordering guarantees where a workflow scope exists.
  For external PRs (no workflow), events MUST be ordered per PR Work Item (§2.3).

### 3.17 Reliability Metric Events

Implementations MUST emit reliability events to power SLO monitoring, error budget
tracking, and degradation mode transitions (see N1 §5.5).

#### reliability/sli-computed

Emitted periodically when SLI values are computed over a rolling window.

```clojure
{:event/type :reliability/sli-computed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long
 :deployment/id string             ; REQUIRED: scope key (§2.3)

 :sli/name keyword                   ; REQUIRED: SLI identifier (SLI-1 through SLI-7, see N1 §5.5.2)
 :sli/value double                   ; REQUIRED: computed value
 :sli/window keyword                 ; REQUIRED: :1h | :7d | :30d
 :sli/tier keyword                   ; OPTIONAL: workflow tier filter
 :sli/dimensions map                 ; OPTIONAL: breakdown by phase, agent, tool, etc.

 :message "SLI computed: {name} = {value} over {window}"}
```

#### reliability/slo-breach

Emitted when an SLO target is missed for `:standard` or `:critical` tiers.

```clojure
{:event/type :reliability/slo-breach
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long
 :deployment/id string             ; REQUIRED: scope key (§2.3)

 :slo/sli-name keyword               ; REQUIRED: which SLI breached
 :slo/target double                  ; REQUIRED: target value
 :slo/actual double                  ; REQUIRED: measured value
 :slo/tier keyword                   ; REQUIRED: workflow tier
 :slo/window keyword                 ; REQUIRED: measurement window

 :message "SLO breach: {sli-name} target={target} actual={actual} tier={tier}"}
```

#### reliability/error-budget-update

Emitted when error budget state is recomputed.

```clojure
{:event/type :reliability/error-budget-update
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long
 :deployment/id string             ; REQUIRED: scope key (§2.3)

 :budget/tier keyword                ; REQUIRED: :standard | :critical
 :budget/sli keyword                 ; REQUIRED: SLI identifier
 :budget/remaining double            ; REQUIRED: 0.0-1.0 fraction remaining
 :budget/burn-rate double            ; REQUIRED: current burn rate (1.0 = nominal)
 :budget/window keyword              ; REQUIRED: :1h | :7d | :30d

 :message "Error budget: tier={tier} sli={sli} remaining={remaining} burn-rate={burn-rate}"}
```

#### reliability/degradation-mode-changed

Emitted when the system transitions between degradation modes (see N1 §5.5.5).

```clojure
{:event/type :reliability/degradation-mode-changed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long
 :deployment/id string             ; REQUIRED: scope key (§2.3)

 :degradation/from keyword           ; REQUIRED: :nominal | :degraded | :safe-mode
 :degradation/to keyword             ; REQUIRED: :nominal | :degraded | :safe-mode
 :degradation/trigger string         ; REQUIRED: what caused the transition

 :message "Degradation mode: {from} → {to} ({trigger})"}
```

### 3.18 Repository Intelligence Events

Implementations MUST emit events for index quality tracking and canary validation
(see N1 §2.27.9–2.27.10).

#### repo-index/quality-computed

Emitted after each incremental index update with quality metrics.

```clojure
{:event/type :repo-index/quality-computed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :repo/id string                     ; REQUIRED: repository identifier
 :revision/commit-sha string         ; REQUIRED: commit at which quality was measured
 :quality/freshness-lag-ms long      ; REQUIRED: ms since last indexed commit
 :quality/coverage-score double      ; REQUIRED: 0.0-1.0
 :quality/symbol-coverage double     ; OPTIONAL
 :quality/search-recall double       ; OPTIONAL: from canary queries

 :message "Index quality: repo={repo/id} coverage={coverage-score}"}
```

#### repo-index/canary-failed

Emitted when index canary queries detect a recall regression.

```clojure
{:event/type :repo-index/canary-failed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :repo/id string                     ; REQUIRED
 :revision/commit-sha string         ; REQUIRED
 :canary/expected-recall double      ; REQUIRED: threshold
 :canary/actual-recall double        ; REQUIRED: measured recall
 :canary/failed-queries [string]     ; OPTIONAL: query IDs that regressed

 :message "Index canary failed: repo={repo/id} recall={actual-recall} < {expected-recall}"}
```

### 3.19 Supervisory Snapshot Events

The supervisory-state component (N5-delta-supervisory-control-plane §3.4) emits
entity-snapshot events whenever a canonical supervisory entity is inserted or
updated. These events carry the **full entity** as specified in
N5-delta-supervisory-control-plane §3 and serve as the single source of
supervisory truth for external consumers (the Rust control console, native
app, web dashboard).

Consumers MAY rely on the invariant that any `:supervisory/*-upserted` event
contains a complete and valid entity per the §3 schema. The supervisory-state
component owns materialization; consumers never reconstruct entities from
fine-grained events directly.

Rules:

- Each entity MUST be keyed by its canonical ID (`:workflow-run/id`,
  `:agent/id`, `[:repo :number]` for PRs, `:policy-eval/id`,
  `:attention/id`).
- A `:supervisory/*-upserted` event SHOULD be emitted at most once per
  state-change burst (coalesce bursts within ≤ 100 ms into a single emission).
- `:attention/resolved? = true` SHALL be encoded as a standard upsert rather
  than a separate deletion event; consumers observe the transition via the
  `:attention/resolved?` field.
- The supervisory-state component reads its own emitted events on startup to
  rebuild its in-memory entity table (§3.4 of N5-delta-supervisory-control-plane).
  Implementations MAY also write periodic full-snapshot events to bound
  startup replay cost.
- Every event in this family MUST carry `:supervisory/schema-version` (string,
  semantic version) alongside `:supervisory/entity`. Startup replay (§3.5
  invariant 3 of N5-delta-supervisory-control-plane) resolves snapshot
  precedence across restarts spanning an entity-shape change; without a
  version discriminator on the snapshot itself, a consumer cannot tell an old
  shape from a new one. This version tracks the entity schema, and is
  independent of the envelope's `:event/version` (§7).

#### 3.19.1 Family Membership

The `:supervisory/*` family is enumerated here; entity shapes are defined by the
owning spec and MUST NOT be duplicated into N3.

| Event type | Entity | Shape defined by | Sole emitter |
|------------|--------|------------------|--------------|
| `:supervisory/workflow-upserted` | WorkflowRun | N5-delta-1 §3.1 | supervisory-state |
| `:supervisory/agent-upserted` | AgentSession | N5-delta-1 §3.1 | supervisory-state |
| `:supervisory/pr-upserted` | PrFleetEntry | N5-delta-1 §3.1, extended N5-delta-2 §4.2 | supervisory-state |
| `:supervisory/policy-evaluated` | PolicyEvaluation | N5-delta-1 §3.1 | supervisory-state |
| `:supervisory/attention-derived` | AttentionItem | N5-delta-1 §3.1 | supervisory-state |
| `:supervisory/intervention-upserted` | InterventionRequest | N5-delta-1 §3.3 | supervisory-state |
| `:supervisory/evidence-upserted` | Evidence | N5-delta-3 §2.1 | supervisory-state |
| `:supervisory/artifact-upserted` | Artifact | N5-delta-3 §2.2 | supervisory-state |
| `:supervisory/task-node-upserted` | TaskNode | N5-delta-3 §2.3 | supervisory-state |
| `:supervisory/decision-upserted` | Decision | N5-delta-3 §2.4 | supervisory-state |
| `:supervisory/pack-manifest-upserted` | PackManifest | N5-delta-3 §2.5 | supervisory-state |
| `:supervisory/automation-edge-upserted` | AutomationEdge | N5-delta-4 §2 | automation-edge-correlator |

Every row carries the §2 envelope plus `:supervisory/entity`,
`:supervisory/schema-version`, and `:supervisory/entity-key` (the §2.3 scope
key, holding the entity's canonical ID). Where the entity belongs to a
workflow, the event SHOULD also carry `:workflow/id` for cross-referencing;
per §2.3 this does not change the event's scope.

The "sole emitter" column is normative: per N5-delta-1 §3.5 invariant 6, no
other component MAY emit a snapshot event for an entity family it does not own.

Adding a member to this family is an N3 change. A delta spec MAY define the
entity shape, but the event type MUST appear in this table before an
implementation emits it.

#### supervisory/workflow-upserted

```clojure
{:event/type :supervisory/workflow-upserted
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long
 :supervisory/entity-key uuid   ; REQUIRED: scope key (§2.3) — the :workflow-run/id
 :supervisory/schema-version string     ; REQUIRED: entity schema version (§3.19)
 :workflow/id uuid

 :supervisory/entity {:workflow-run/id          uuid
                      :workflow-run/workflow-key string
                      :workflow-run/intent       string
                      :workflow-run/status       keyword    ; :queued :running :paused :blocked :completed :failed :cancelled
                      :workflow-run/current-phase keyword
                      :workflow-run/started-at   inst
                      :workflow-run/updated-at   inst
                      :workflow-run/trigger-source keyword  ; :mcp :cli :api :chain
                      :workflow-run/correlation-id uuid}

 :message "Workflow {workflow-key} upserted"}
```

#### supervisory/agent-upserted

```clojure
{:event/type :supervisory/agent-upserted
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long
 :supervisory/entity-key uuid   ; REQUIRED: scope key (§2.3) — the :agent/id
 :supervisory/schema-version string     ; REQUIRED: entity schema version (§3.19)

 :supervisory/entity {:agent/id                   uuid
                      :agent/vendor               keyword   ; :claude-code :codex :miniforge-tui ...
                      :agent/external-id          string
                      :agent/name                 string
                      :agent/status               keyword   ; :idle :starting :executing :blocked :completed :failed :unreachable :unknown
                      :agent/capabilities         [keyword]
                      :agent/heartbeat-interval-ms int
                      :agent/metadata             map
                      :agent/tags                 [string]
                      :agent/registered-at        inst
                      :agent/last-heartbeat       inst
                      :agent/task                 (maybe string)}

 :message "Agent {name} upserted"}
```

#### supervisory/pr-upserted

```clojure
{:event/type :supervisory/pr-upserted
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long
 :supervisory/entity-key [string long]   ; REQUIRED: scope key (§2.3) — [repo number]
 :supervisory/schema-version string     ; REQUIRED: entity schema version (§3.19)

 :supervisory/entity {:pr/repo                string
                      :pr/number              long
                      :pr/url                 string
                      :pr/branch              string
                      :pr/title               string
                      :pr/status              keyword   ; :draft :open :reviewing :changes-requested :approved :merging :merged :closed :failed
                      :pr/merge-order         int
                      :pr/depends-on          [int]
                      :pr/blocks              [int]
                      :pr/ci-status           keyword   ; :pending :running :passed :failed :skipped
                      :pr/author              (maybe string)
                      :pr/behind-main         (maybe boolean)
                      :pr/merged-at           (maybe inst)}

 :message "PR {repo}#{number} upserted"}
```

#### supervisory/policy-evaluated

```clojure
{:event/type :supervisory/policy-evaluated
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long
 :supervisory/entity-key uuid   ; REQUIRED: scope key (§2.3) — the :policy-eval/id
 :supervisory/schema-version string     ; REQUIRED: entity schema version (§3.19)

 :supervisory/entity {:policy-eval/id            uuid
                      :policy-eval/target-type   keyword    ; :pr :artifact :workflow-output
                      :policy-eval/target-id     any        ; [repo number] for PRs, uuid otherwise
                      :policy-eval/passed?       boolean
                      :policy-eval/packs-applied [string]
                      :policy-eval/violations    [{:violation/rule-id     keyword
                                                   :violation/severity    keyword  ; :critical :high :medium :low :info
                                                   :violation/category    keyword
                                                   :violation/message     string
                                                   :violation/location    (maybe string)
                                                   :violation/remediable? boolean}]
                      :policy-eval/evaluated-at  inst}

 :message "Policy evaluation {id}: {passed?}"}
```

Unlike the other supervisory events, `:supervisory/policy-evaluated` is
**immutable** — a re-evaluation produces a new entity with a new
`:policy-eval/id`, never mutating a prior one (N5-delta-supervisory-control-plane §3.2).

#### supervisory/attention-derived

```clojure
{:event/type :supervisory/attention-derived
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long
 :supervisory/entity-key uuid   ; REQUIRED: scope key (§2.3) — the :attention/id
 :supervisory/schema-version string     ; REQUIRED: entity schema version (§3.19)

 :supervisory/entity {:attention/id          uuid
                      :attention/severity    keyword   ; :critical :warning :info
                      :attention/source-type keyword   ; :workflow :pr :train :policy :agent
                      :attention/source-id   any
                      :attention/summary     string
                      :attention/derived-at  inst
                      :attention/resolved?   boolean}

 :message "Attention {severity}: {summary}"}
```

The supervisory-state component derives attention items from the other entity
tables per N5-delta-supervisory-control-plane §5.1 and emits an upsert
whenever an attention condition changes (including resolution via
`:attention/resolved? = true`).

### 3.20 Data Foundry Events

For Data Foundry data pipeline execution (see Data Foundry N1–N4), implementations MUST
emit the following event types. All events use the `:data-foundry/` namespace prefix.

#### data-foundry/pipeline-started

```clojure
{:event/type :data-foundry/pipeline-started
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :pipeline/id uuid
 :pipeline/name string
 :pipeline/version string
 :pipeline-run/id uuid
 :pipeline-run/mode keyword            ; :full-refresh | :incremental | :backfill | :reprocess

 :message "Data pipeline started: {pipeline.name} ({mode})"}
```

#### data-foundry/stage-completed

```clojure
{:event/type :data-foundry/stage-completed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :pipeline-run/id uuid
 :stage/id uuid
 :stage/family keyword                  ; :ingest | :extract | :normalize | :transform | :aggregate | :validate | :enrich | :publish
 :stage/records-in long
 :stage/records-out long
 :stage/duration-ms long

 :message "Pipeline stage completed: {stage.family}"}
```

#### data-foundry/pipeline-completed

```clojure
{:event/type :data-foundry/pipeline-completed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :pipeline-run/id uuid
 :pipeline/id uuid
 :pipeline-run/duration-ms long
 :pipeline-run/records-published long
 :pipeline-run/output-dataset-versions [uuid ...]

 :message "Data pipeline completed: {pipeline.name}"}
```

#### data-foundry/pipeline-failed

```clojure
{:event/type :data-foundry/pipeline-failed
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :pipeline-run/id uuid
 :pipeline/id uuid
 :pipeline-run/failed-stage-id uuid
 :pipeline-run/failure-reason string
 :failure/class keyword

 :message "Data pipeline failed: {pipeline.name} at stage {stage.id} — {failure-reason}"}
```

#### data-foundry/quality-evaluated

```clojure
{:event/type :data-foundry/quality-evaluated
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :pipeline-run/id uuid
 :quality-pack/id string
 :quality-pack/version string
 :quality-eval/verdict keyword          ; :pass | :fail | :warning
 :quality-eval/rule-count long
 :quality-eval/violation-count long
 :quality-eval/blocking? boolean
 :dataset/id uuid

 :message "Quality pack evaluated: {pack.id} — {verdict}"}
```

#### data-foundry/lineage-edge-created

```clojure
{:event/type :data-foundry/lineage-edge-created
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :lineage-edge/source-dataset-id uuid
 :lineage-edge/target-dataset-id uuid
 :lineage-edge/pipeline-run-id uuid
 :lineage-edge/stage-id uuid
 :lineage-edge/transformation-type keyword  ; OPTIONAL

 :message "Lineage edge created: {source} → {target}"}
```

#### data-foundry/freshness-sla-breach

```clojure
{:event/type :data-foundry/freshness-sla-breach
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :dataset/id uuid
 :dataset/name string
 :sla/max-age-hours long
 :sla/actual-age-hours double
 :sla/last-refresh inst

 :message "Freshness SLA breach: {dataset.name} — {actual-age-hours}h (limit: {max-age-hours}h)"}
```

#### data-foundry/schema-drift-detected

```clojure
{:event/type :data-foundry/schema-drift-detected
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :event/sequence-number long

 :dataset/id uuid
 :schema/expected-version string
 :schema/observed-hash string
 :schema/changes [{:field string :change-type keyword}]  ; :field-added | :field-removed | :type-changed

 :message "Schema drift detected: {dataset.id} — {change-count} field changes"}
```

All Data Foundry events MUST link to pipeline-run/id for correlation. Events that
produce evidence bundles MUST include an `:evidence-bundle-id` field per N6.

### 3.21 Workflow Control & Checkpoint Events

§3.1 covers the nominal workflow path (started → phases → completed/failed).
This section covers the control path: operator cancellation (N2 §5), and the
checkpoint/resume contract of N2-delta-phase-checkpoint-and-resume §9.

#### workflow/cancelled

A terminal state distinct from `:workflow/failed` — the workflow did not fail,
it was stopped. `:workflow/completed` MUST NOT be used to report cancellation
even though its `:workflow/status` enum admits `:cancelled`; consumers that
count failures MUST NOT count cancellations.

```clojure
{:event/type :workflow/cancelled
 :workflow/id uuid
 :workflow/phase keyword               ; OPTIONAL: phase active at cancellation

 :cancel/requested-by string           ; REQUIRED: principal that requested it
 :cancel/source keyword                ; REQUIRED: :cli | :api | :tui | :control-action | :supervisor
 :cancel/reason string                 ; OPTIONAL: free-text
 :workflow/duration-ms long

 :message "Workflow cancelled by {requested-by}"}
```

When cancellation arrives via the N8 control interface, `:cancel/source` MUST be
`:control-action` and the event MUST carry `:action/id` linking to the
originating `control-action/requested` (§3.15).

#### workflow/checkpoint-written

```clojure
{:event/type :workflow/checkpoint-written
 :workflow/id uuid
 :phase/name keyword
 :checkpoint/path string
 :checkpoint/size-bytes long

 :message "Checkpoint written for phase {phase/name}"}
```

#### workflow/checkpoint-write-failed

```clojure
{:event/type :workflow/checkpoint-write-failed
 :workflow/id uuid
 :phase/name keyword
 :error/message string                 ; REQUIRED: human-readable description
 :failure/class keyword                ; REQUIRED: canonical class (see N1 §5.3.3)

 :message "Checkpoint write failed for phase {phase/name}: {error/message}"}
```

#### workflow/machine-snapshot-written

```clojure
{:event/type :workflow/machine-snapshot-written
 :workflow/id uuid
 :machine/state keyword
 :snapshot/path string
 :snapshot/size-bytes long

 :message "Machine snapshot written at state {machine/state}"}
```

#### workflow/machine-snapshot-write-failed

```clojure
{:event/type :workflow/machine-snapshot-write-failed
 :workflow/id uuid
 :machine/state keyword
 :error/message string                 ; REQUIRED: human-readable description
 :failure/class keyword                ; REQUIRED: canonical class (see N1 §5.3.3)

 :message "Machine snapshot write failed at {machine/state}: {error/message}"}
```

#### workflow/resumed

```clojure
{:event/type :workflow/resumed
 :workflow/id uuid
 :from-state keyword                   ; REQUIRED: machine state resumed from
 :from-phase keyword                   ; REQUIRED: phase resumed from
 :skipping [keyword ...]               ; REQUIRED: phases skipped as already complete

 :message "Workflow resumed at {from-phase}, skipping {n} completed phases"}
```

A resumed workflow continues the sequence of its original run: sequence numbers
MUST NOT reset, and `:workflow/started` MUST NOT be re-emitted. Replay
determinism (§2.2) is evaluated over the concatenated pre- and post-resume
stream.

#### workflow/spec-hash-mismatch

Emitted when a resume detects that the workflow spec changed since the
checkpoint was written.

```clojure
{:event/type :workflow/spec-hash-mismatch
 :workflow/id uuid
 :expected-hash string
 :actual-hash string

 :message "Spec hash mismatch on resume: expected {expected-hash}"}
```

This event is advisory on its own. Whether a mismatch aborts the resume is
governed by N2-delta §9; N3 only fixes the wire shape.

---

## 4. Event Emission Requirements

### 4.1 Emission Points

Implementations MUST emit events at these points:

1. **Workflow boundaries** - start, phase transitions, completion
2. **Agent boundaries** - start, completion, failure
3. **Agent activity** - status updates (≥1 per 5 seconds during active work)
4. **Tool invocations** - all tool uses
5. **LLM calls** - all requests and responses
6. **Gate execution** - start, pass, fail
7. **Inter-agent messages** - all communications
8. **Milestones** - significant progress points
9. **Task lifecycle** - frontier entry, claims, capability binding, scope violations
10. **OPSV lifecycle** - experiment plans, load steps, guardrail aborts, policy proposals, verification results (N7)
11. **Listener lifecycle** - attach, detach, control actions, annotations (N8)
12. **External PR lifecycle** - provider events, readiness/risk/policy changes, train changes (N9)
13. **Reliability metrics** - SLI computations, SLO breaches, error budget updates, degradation mode changes
14. **Repository intelligence** - index quality metrics, canary validation results
15. **ETL and pack promotion** - ETL stages, pack generation and promotion (§3.11)
16. **Pack lifecycle and Pack Runs** - install/update/remove, run boundaries, capability denials, chain edges (§3.12)
17. **Meta-loop refusals** - halt requests from meta-supervision agents (§3.7b)
18. **Supervisory snapshots** - entity upserts from the supervisory-state component (§3.19)
19. **Data Foundry pipelines** - pipeline and stage boundaries, quality, lineage, freshness, schema drift (§3.20)
20. **Workflow control** - cancellation, checkpoint writes, resume (§3.21)

Every event type defined in §3 has an emission point in this list. A family added
to §3 without a corresponding entry here is an incomplete amendment.

### 4.2 Throttling

Implementations MAY throttle status events to prevent event storm:

- MUST NOT emit more than 2-3 status events per second per agent
- MAY batch rapid-fire events (e.g., reading multiple files)
- MUST emit at least one status event per 5 seconds during active work

### 4.3 Event Storage

Implementations MUST:

1. **Store events durably** - Events MUST persist across process restarts
2. **Maintain ordering** - Storage MUST preserve sequence numbers
3. **Support replay** - Events MUST be retrievable in sequence order

#### 4.3.1 Retention Classes

Every event type belongs to exactly one retention class. The class is a property
of the type, declared in the §6 registry.

| Class | Minimum retention | Members |
|-------|-------------------|---------|
| `:ephemeral` | 24 hours | `:agent/status`, `:workflow/phase-heartbeat`, LLM request/response bodies |
| `:operational` | 30 days | Tool use, milestones, task lifecycle, listener lifecycle, reliability metrics, repo intelligence |
| `:durable` | Life of the scope's record — the workflow, PR Work Item, pack, repository, entity, or deployment the event is scoped to (§2.3) | Workflow/agent/phase lifecycle, gates, pack lifecycle and Pack Runs, capability denials, PR lifecycle, supervisory snapshots, workflow control (§3.21) |
| `:audit` | Per deployment policy, minimum 1 year | `capability/denied`, `task/scope-violation`, `control-action/*`, `meta-loop/halt-requested`, `pack/promoted`, and every event carrying `:failure/class` |

Implementations MUST NOT expire an event before its class minimum.
Implementations MAY retain longer. A deployment MUST document its actual
retention if it exceeds these minima.

#### 4.3.2 Retention and Replay Interact

Expiring an event narrows the replay horizon. Implementations MUST:

1. Track the oldest retained sequence number per scope and expose it as
   `:oldest-available` on the HTTP 410 response of §5.3.5.
2. Never expire an event of class `:durable` or `:audit` while its scope
   (§2.3) is still live — a non-terminal workflow, an open PR Work Item, an
   installed pack, a tracked repository, a current entity, a running
   deployment. Replay determinism (§2.2) is unachievable for a live scope
   whose own lifecycle events have been collected.
3. Expire whole prefixes only. Expiring an event from the middle of a scope's
   sequence breaks causal ordering and is non-conformant.

#### 4.3.3 Archival

An implementation MAY move expired events to cold storage rather than deleting
them. Archived events remain subject to the redaction rules of §8: archival is
not an exemption from redaction, and an implementation MUST NOT archive a
payload it would have been required to redact on the wire.

---

## 5. Event Stream API

### 5.1 Subscription Protocol

Implementations MUST provide:

```clojure
;; Subscribe to workflow events
(subscribe-to-workflow workflow-id callback-fn)
;; Returns: subscription handle

;; Subscribe to events for a PR Work Item (§2.3 scope key).
;; REQUIRED: this is the only way to observe events with a nil :workflow/id.
(subscribe-to-pr pr-id callback-fn)
;; Returns: subscription handle

;; Subscribe to all fleet events
(subscribe-to-fleet callback-fn)
;; Returns: subscription handle

;; Unsubscribe
(unsubscribe subscription-handle)
```

A Miniforge-originated PR emits events carrying both `:workflow/id` and
`:pr/id` (§2.3). Such an event MUST be delivered to subscribers on both scopes.
Implementations MUST NOT deliver it twice to a subscriber holding both handles
for the same underlying consumer — de-duplication is by `:event/id`.

### 5.2 Query API

Implementations MUST support:

```clojure
;; Get events for workflow
(get-events workflow-id {:offset long :limit long})
;; Returns: sequence of events

;; Get events for a PR Work Item (§2.3 scope key)
(get-events-for-pr pr-id {:offset long :limit long})
;; Returns: sequence of events, ordered per PR Work Item

;; Get events by type
(get-events-by-type workflow-id event-type {:offset long :limit long})

;; Get latest status
(get-latest-status workflow-id agent-id)
;; Returns: most recent agent/status event
```

Query results MUST be ordered by `:event/sequence-number` ascending within the
requested scope. Where retention (§4.3) has removed a prefix, implementations
MUST report the oldest available sequence number rather than silently returning
a truncated range.

### 5.3 Streaming Endpoints (HTTP)

Implementations MUST provide a Server-Sent Events (SSE) endpoint and MAY
provide a WebSocket endpoint. Both carry the same event envelope (§2) and
the same ordering guarantees (§2.2). This section is the wire contract for
the per-workflow stream; cross-workflow aggregation endpoints are out of
scope for OSS and defined by downstream products.

#### 5.3.1 Endpoint

```http
GET  /api/workflows/:id/stream          ; per-workflow SSE
WS   /api/workflows/:id/stream          ; OPTIONAL WebSocket alternative
```

#### 5.3.2 Authentication

The endpoint MUST accept a bearer token via:

```http
Authorization: Bearer <token>
```

For SSE, the token MAY additionally be supplied via a cryptographically-random
query parameter `?access_token=<token>` to accommodate browsers that cannot
set headers on `EventSource`. Implementations MUST then either (a) require the
token to be single-use and short-lived, or (b) reject it entirely.

Tokens resolve to a principal + RBAC role (N8 §2.3). Unauthenticated requests
MAY succeed in local mode with `localhost`-only binding but MUST fail with
HTTP 401 in any network-exposed deployment.

#### 5.3.3 Listener Attach Handshake

The SSE/WebSocket connection IS the listener attach per N8 §2.1. On connection:

1. The client MAY send listener registration metadata via request headers:

    ```http
    X-Listener-Id:          <uuid>          ; OPTIONAL — client-chosen id
    X-Listener-Type:        watcher|dashboard
    X-Listener-Capability:  observe|advise|control
    X-Listener-Buffer-Size: <int>           ; OPTIONAL — server MAY cap
    ```

   The server MUST validate that the authenticated principal's RBAC role
   permits the declared `X-Listener-Capability`. If not, respond HTTP 403.

2. The server MUST emit a `listener/attached` event (N8 §10) as the first
   event on the stream, echoing the assigned listener id:

    ```text
    event: listener-attached
    data: {"event/type":"listener/attached","listener/id":"...",
           "listener/capability":"observe","sequence-number":0}
    ```

3. The server MUST emit a `listener/detached` event (N8 §10) immediately
   before closing the stream, including a `:listener/reason` of
   `:disconnect | :timeout | :revoked`.

`ADVISE` and `CONTROL` listeners MAY emit annotations or request control
actions over a separate bidirectional channel (WebSocket), or via parallel
HTTP POST requests to OCI endpoints (N8 §9). SSE is strictly server-to-client.

#### 5.3.4 Subscription Filters

Clients MAY restrict the event stream via query parameters:

```text
?event-type=<keyword>      (repeatable; accepts glob, e.g. pack.run/*)
&phase=<keyword>           (repeatable)
&agent=<keyword>           (repeatable)
&pr-id=<uuid>              (repeatable)
&from-sequence=<long>      (resume — see §5.3.5)
&include-payloads=true|false (default: true)
&sampling-rate=<float>     (0.0–1.0; default 1.0)
```

Filter evaluation is server-side; un-filtered events MUST NOT cross the wire.

#### 5.3.5 Resume-from-Sequence

Every event MUST carry a monotonic `:event/sequence-number` within its scope (§2.3).

On reconnect, clients MAY supply `?from-sequence=<N>` to resume. The server
MUST:

1. Replay events with `sequence-number > N` in ascending order, followed by
   live events
2. If `N` is older than the server's retention horizon, respond HTTP 410 Gone
   with body `{:error "sequence-out-of-retention", :oldest-available <long>}`
   so the client can re-subscribe from a valid position
3. Emit SSE comment lines to mark the catch-up → live transition:

    ```text
    : catch-up-start from=123 to=456
    event: agent-status
    data: {...,"sequence-number":124}
    ...
    : catch-up-end at=456
    ```

For SSE, servers MUST honor the standard `Last-Event-ID` header as an
alternative to `?from-sequence=` — both resume the same way.

#### 5.3.6 Backpressure and Buffer Overflow

If a listener falls behind the server's send buffer (default: 1000 events per
N8 §2.1 `:buffer-size`), the server MUST either:

1. Drop oldest events and emit a `listener/overflow` event with the number of
   dropped events, then continue; OR
2. Disconnect the listener with `listener/detached` reason `:timeout`.

Choice is per-implementation but MUST be consistent within a deployment and
MUST be documented. `CONTROL` listeners SHOULD prefer option (2) to maintain
ordering integrity; `OBSERVE` listeners MAY prefer option (1).

#### 5.3.7 SSE Wire Format

SSE responses MUST use these fields per event:

```text
event: <event-type-short>     ; dashed, e.g. "agent-status", "pack-run-started"
id: <sequence-number>         ; decimal; enables Last-Event-ID resume
data: <json>                  ; JSON serialization of the full event envelope
retry: <milliseconds>         ; OPTIONAL; reconnection hint
```

Multi-line `data:` fields are permitted per the SSE specification; clients
MUST reassemble before parsing JSON.

The server MUST emit an SSE comment (`:`) heartbeat at least every 30 seconds
to prevent proxy idle-timeout disconnects.

#### 5.3.8 WebSocket Wire Format (Optional)

If provided, WebSocket MUST use text frames carrying JSON messages:

```json
{"kind":"event","event":{...full envelope...}}
{"kind":"listener-attached","listener-id":"...","sequence-number":0}
{"kind":"listener-overflow","dropped":42}
{"kind":"pong","at":"..."}
```

Client-to-server frames (for `ADVISE`/`CONTROL` listeners):

```json
{"kind":"annotation","annotation":{...N8 §4 schema...}}
{"kind":"control-action","action":{...N8 §3.2 schema...}}
{"kind":"ping","at":"..."}
```

The server MUST send `pong` within 5 seconds of receiving `ping` or terminate
the connection.

#### 5.3.9 Rate Limiting and Quotas

Implementations MUST enforce per-principal connection limits:

- Default: 10 concurrent streaming connections per principal
- Configurable per RBAC role
- Exceeding the limit: HTTP 429 with `Retry-After` header

Per-connection event-emission rate is governed by N3 §4.2 throttling, which
applies equally regardless of listener count.

---

## 6. Event Type Registry

§3 defines event types family by family. This section is the flat, enumerable
view of the same contract: the set of `:event/type` values an implementation
may emit, and the properties every consumer needs before it has parsed a
payload.

An `:event/type` MUST appear in this registry to be emitted. An implementation
that emits an unregistered type is non-conformant, and a consumer MUST treat an
unregistered type per the unknown-type rule of §7.3.

**Columns.** _Scope_ is the scope key of §2.3. _Retention_ is the class of
§4.3.1. Both are properties of the type, not of a particular emission.

| § | Family | Scope | Retention | Event types |
|---|--------|-------|-----------|-------------|
| 3.1 | Workflow lifecycle | workflow | durable | `workflow/started`, `workflow/phase-started`, `workflow/phase-completed`, `workflow/completed`, `workflow/failed` |
| 3.2 | Agent lifecycle | workflow | durable | `agent/started`, `agent/completed`, `agent/failed` |
| 3.3 | Agent status | workflow | ephemeral | `agent/status` |
| 3.4 | Subagent | workflow | operational | `subagent/spawned` |
| 3.5 | Tool use | workflow | operational | `tool/invoked`, `tool/completed` |
| 3.6 | LLM calls | workflow | ephemeral | `llm/request`, `llm/response` |
| 3.7 | Inter-agent messages | workflow | operational | `agent/message-sent`, `agent/message-received` |
| 3.7b | Meta-loop halt | workflow | audit | `meta-loop/halt-requested` |
| 3.8 | Milestones | workflow | operational | `milestone/reached` |
| 3.9 | Gates | workflow | durable | `gate/started`, `gate/passed`, `gate/failed` |
| 3.10 | PR lifecycle (DAG) | workflow | durable | `pr/opened`, `pr/ci-passed`, `pr/ci-failed`, `pr/review-approved`, `pr/review-changes-requested`, `pr/comment-actionable`, `pr/fix-pushed`, `pr/merged`, `pr/closed` |
| 3.11 | ETL and pack promotion | workflow | durable; `pack/promoted` is audit | `etl/started`, `etl/sources-classified`, `etl/safety-scan-completed`, `etl/completed`, `etl/failed`, `pack/generated`, `pack/promoted` |
| 3.12 | Pack lifecycle | pack | durable | `pack/installed`, `pack/updated`, `pack/removed` |
| 3.12 | Pack Runs and chains | workflow | durable; `capability/denied` is audit | `pack.run/started`, `pack.run/completed`, `pack.run/failed`, `capability/denied`, `chain.edge/started`, `chain.edge/completed`, `chain.edge/failed` |
| 3.13 | Task lifecycle | workflow | operational; `task/scope-violation` is audit | `task/frontier-entered`, `task/claimed`, `task/capability-bound`, `task/scope-violation`, `task/skip-propagated` |
| 3.14 | OPSV (N7) | workflow | durable | `opsv.experiment/planned`, `opsv.experiment/started`, `opsv/load-step`, `opsv.guardrail/abort`, `opsv.convergence/iteration`, `opsv.policy/proposed`, `opsv.verification/result`, `opsv.actuation/emitted`, `opsv.drift/detected` |
| 3.15 | Observability control (N8) | workflow | operational; `control-action/*` is audit | `listener/attached`, `listener/detached`, `listener/overflow`, `control-action/requested`, `control-action/executed`, `control-action/approval-required`, `annotation/created` |
| 3.16 | External PR (N9) | pr | durable | `provider/event-received`, `pr.readiness/changed`, `pr.risk/changed`, `pr.policy/changed`, `pr.state/changed`, `train/changed` |
| 3.17 | Reliability metrics | deployment | operational | `reliability/sli-computed`, `reliability/slo-breach`, `reliability/error-budget-update`, `reliability/degradation-mode-changed` |
| 3.18 | Repository intelligence | repo | operational | `repo-index/quality-computed`, `repo-index/canary-failed` |
| 3.19 | Supervisory snapshots | supervisory entity | durable | The twelve types enumerated in §3.19.1 |
| 3.20 | Data Foundry | workflow | durable | `data-foundry/pipeline-started`, `data-foundry/stage-completed`, `data-foundry/pipeline-completed`, `data-foundry/pipeline-failed`, `data-foundry/quality-evaluated`, `data-foundry/lineage-edge-created`, `data-foundry/freshness-sla-breach`, `data-foundry/schema-drift-detected` |
| 3.21 | Workflow control | workflow | durable | `workflow/cancelled`, `workflow/checkpoint-written`, `workflow/checkpoint-write-failed`, `workflow/machine-snapshot-written`, `workflow/machine-snapshot-write-failed`, `workflow/resumed`, `workflow/spec-hash-mismatch` |

### 6.1 Registry Maintenance

The registry and §3 are two views of one contract and MUST agree. Adding an
event type requires, in the same change:

1. A schema in §3 under its family.
2. A row (or an addition to an existing row) in §6.
3. An emission point in §4.1.

An implementation SHOULD enforce agreement mechanically — a test that fails
when a type is emitted, or a schema is registered, without a matching registry
entry. Drift between §3 and §6 is a spec defect, not an implementation detail.

### 6.2 Naming Rules

Event type keywords are part of the wire contract and MUST follow these rules:

- Namespaced keyword, lower-kebab-case in both namespace and name.
- The namespace names the **subject** the event is about, not the component
  that emitted it. A halt requested by the conflict detector is
  `:meta-loop/halt-requested`, not `:conflict-detector/halt`.
- The name is a past-tense verb phrase — events report what happened, not what
  is being requested of a consumer. `:pr/merged`, not `:pr/merge`.
- A dotted namespace (`:pack.run/started`, `:opsv.experiment/planned`) denotes
  a sub-subject of the parent namespace. Consumers MUST NOT assume a dotted
  namespace can be truncated to its parent for filtering; `pack.run/*` and
  `pack/*` are distinct globs (§5.3.4).
- A constructor function name is not part of the contract. Where a constructor
  name and its emitted `:event/type` differ, the `:event/type` is authoritative.

---

## 7. Schema Evolution & Compatibility

`:event/version` is REQUIRED on every event (§2.1) but means nothing without
rules for when it changes and what a consumer does when it disagrees. This
section supplies them.

### 7.1 What `:event/version` Versions

`:event/version` is the semantic version of the **payload schema of that
event type**, not of the spec, the envelope, or the emitting implementation. Two
event types evolve independently: `:agent/status` at "1.2.0" and
`:gate/failed` at "2.0.0" is a valid stream.

The envelope itself (§2) is versioned by this spec's own version, not by
`:event/version`. An envelope change is a change to N3.

### 7.2 Change Classification

For a given event type:

| Change | Version bump | Allowed |
|--------|--------------|---------|
| Adding an OPTIONAL field | minor | Yes |
| Adding a value to an open keyword field | minor | Yes |
| Documentation-only correction | patch | Yes |
| Adding a REQUIRED field | major | Yes, with §7.4 |
| Removing or renaming any field | major | Yes, with §7.4 |
| Narrowing a field's type or range | major | Yes, with §7.4 |
| Adding a value to a **closed** vocabulary | major | Yes, with §7.4 |
| Changing the meaning of a field without changing its name | — | **No.** Introduce a new field |

The last row is not a versioning question. A field whose meaning silently
changes defeats replay: a stream replayed under the new reading produces a
different state than it did when emitted, violating §2.2. Retire the field and
add a new one.

`:halt/reason-code` (§3.7b) is the worked example of a closed vocabulary:
adding a RefusalReason is a major bump for `:meta-loop/halt-requested` and a
deliberate change to this spec.

### 7.3 Consumer Obligations

Consumers MUST tolerate a stream richer than the schema they were built
against. Specifically:

1. **Unknown event type** — a consumer MUST ignore it and continue. It MUST NOT
   error, drop the connection, or stop advancing its sequence position. A
   consumer that halts on an unknown type makes every future additive change a
   breaking one.
2. **Unknown field on a known type** — a consumer MUST ignore the field and
   process the rest of the event. Open-schema validation (Malli `:map` with
   required keys, unknown keys passing through) satisfies this.
3. **Higher minor version than expected** — process normally under rules 1–2.
4. **Higher major version than expected** — a consumer MUST NOT silently
   process the event. It MUST either handle the new major explicitly or skip
   the event and record that it did so. Silently applying a major-version
   payload is how a consumer produces a plausible, wrong projection.
5. **Sequence integrity** — skipping an event for any reason above MUST NOT
   cause the consumer to skip a sequence number. Resume (§5.3.5) depends on the
   consumer's position being accurate even for events it declined to interpret.

Rules 1 and 2 are what let a delta spec add a field without a coordinated
fleet-wide upgrade. Rule 4 is what stops that latitude from becoming silent
data loss.

### 7.4 Major Version Procedure

A major bump to an event type MUST:

1. State the change in this spec's version history with the affected type.
2. Keep the type's `:event/type` keyword stable. A payload change is a version
   change, not a new event type. Renaming the type instead of bumping it
   converts a detectable break into a silent one — under §7.3 rule 1 every
   consumer would ignore the renamed type and report no error.
3. Ship the schema change and the registry (§6) update together.

Because the product is pre-release, N3 does not require dual-emission of old
and new payload shapes during a transition. Implementations cut over.

---

## 8. Sensitive Data & Redaction

The event stream carries agent context, tool arguments, LLM prompts, PR comment
bodies, and file paths. §12.1 claims the stream as an audit trail for SOC 2 and
FedRAMP; an audit trail that also functions as an exfiltration path for
credentials is worse than no audit trail. This section is the contract that
makes the claim in §12.1 defensible.

### 8.1 Never-Emitted Values

Implementations MUST NOT emit the following in any event field, including
inside free-text `:message`, `:status/detail`, and error payloads:

- Credentials of any kind: API keys, tokens, passwords, private keys, session
  cookies, connection strings containing a secret.
- The contents of files matched by the deployment's secret-file policy
  (`.env`, keystores, credential JSON, and equivalents).
- Values a policy pack has classified as secret (N4).

This is a MUST NOT on emission, not a filter on delivery. A redacting sink does
not make a secret-bearing event conformant — the event is already in memory,
already sequenced, and already durable per §4.3. Redact at construction.

### 8.2 Redaction Marker

Where a field would have carried a value excluded by §8.1, implementations MUST
substitute the marker `"[REDACTED]"` rather than omitting the key.

An omitted key is indistinguishable from a field that was never populated,
which makes redaction invisible to audit. A present marker records that
something existed and was withheld.

```clojure
{:event/type :tool/invoked
 :tool/name :run-command
 :tool/args {:command "deploy.sh" :env {:API_TOKEN "[REDACTED]"}}
 :message "Running deploy.sh"}
```

### 8.3 Truncation

Fields carrying unbounded content — `:message/content`, `:comment/body`,
`:agent/context`, `:tool/args`, `:tool/result`, `:agent/output` — MAY be
truncated. When truncated, an implementation MUST:

1. Mark the field as truncated rather than silently shortening it, using the
   suffix `"…[truncated]"`.
2. Preserve the full content in the evidence bundle per N6 where the workflow
   requires it to be recoverable.

Truncation is a transport concern; evidence completeness is N6's. Truncating on
the stream MUST NOT be the reason a required artifact is unrecoverable.

### 8.4 Field Classification

Every event field is one of:

| Class | Meaning | Delivery |
|-------|---------|----------|
| `:public` | Identifiers, enums, counters, durations, hashes | Always delivered |
| `:payload` | Free text, args, results, context, diffs | Suppressed when `include-payloads=false` (§5.3.4) |
| `:restricted` | Fields a deployment's policy marks sensitive | Delivered only to principals whose RBAC role permits it (N8 §2.3) |

Implementations MUST honor `include-payloads=false` by suppressing every
`:payload`-class field while still delivering the event. A consumer that only
needs the shape of a workflow MUST be able to obtain it without receiving any
free text.

`:restricted` fields MUST be suppressed per-recipient at delivery, not
per-event at emission — two listeners on the same stream may be entitled to
different views of the same event.

### 8.5 Retention Interaction

The retention classes of §4.3.1 apply to events; the rules here apply to
fields. An `:audit`-class event retained for a year retains its `[REDACTED]`
markers for a year. Retention never restores a redacted value, and §4.3.3
archival never exempts one.

---

## 9. Emission Failure Semantics

§4.1 says implementations MUST emit at defined points. What happens when the
emission itself fails is a separate question, and answering it "log and
continue" for every case would make the guarantees in §2.2 and §10.2
unenforceable — a stream missing `:gate/failed` replays into a state where the
gate passed.

### 9.1 Fail-Closed Classes

Emission of an event whose retention class (§4.3.1) is `:durable` or `:audit`
MUST be treated as part of the operation that triggered it. If the event cannot
be durably recorded, the implementation MUST:

1. Fail the triggering operation, and
2. Surface the failure through the workflow's own failure path
   (`:workflow/failed` with `:failure/class`), if that path is itself still
   available.

A workflow MUST NOT report success on a stream that is missing the events
proving it. The specific hazard is `capability/denied` and
`task/scope-violation` (§8 audit class): an implementation that blocks the
operation but drops the event has enforced the policy and lost the evidence,
which is indistinguishable from never having been asked.

### 9.2 Fail-Open Classes

Emission of an `:ephemeral` or `:operational` class event MAY fail without
failing the triggering operation. Implementations MUST:

1. Increment a dropped-event counter, and
2. Reflect the drop in SLI computation (N1 §5.5.2) rather than absorbing it
   silently.

Dropping `:agent/status` degrades the UI. Dropping it without counting degrades
the UI and the reliability signal that would have revealed it.

### 9.3 Sequence Integrity Under Failure

A failed emission MUST NOT consume a sequence number. Sequence numbers are
allocated on successful durable record, not on attempt.

Allocating on attempt produces gaps that a consumer cannot distinguish from
retention expiry (§4.3.2) or from events it declined to interpret (§7.3 rule
5) — three different conditions collapsed into one unreadable symptom.

### 9.4 Backpressure Is Not Failure

The buffer-overflow behavior of §5.3.6 governs one slow _listener_ and MUST NOT
be conflated with the rules here. A listener falling behind is a delivery
problem, handled per §5.3.6. It MUST NOT cause an event to be dropped from
storage, and it MUST NOT fail the triggering operation for any class.

---

## 10. Conformance & Testing

### 10.1 Schema Validation

Implementations MUST:

1. Validate all emitted events against schema
2. Reject invalid events with clear error messages
3. Log validation failures

Validation MUST be open per §7.3 rule 2: a required-key check that permits
unknown keys. A closed schema that rejects unknown keys converts every additive
change in a delta spec into a breaking one.

### 10.2 Event Replay Tests

Conformance tests MUST verify:

1. Replaying events reproduces workflow state
2. Sequence numbers are monotonic
3. Causal ordering is preserved
4. All required event types are emitted

### 10.3 Performance Requirements

Implementations MUST:

- Emit events with <10ms latency (p99)
- Support ≥100 subscriptions per workflow
- Stream events to clients with <100ms latency (p99)

### 10.4 Conformance Requirements

Requirement IDs are stable identifiers for the normative statements of this
spec, so a conformance suite can cite what it tests and a gap analysis can cite
what is missing. IDs are never reused; a withdrawn requirement is marked
withdrawn, not deleted.

#### Envelope and ordering

| ID | Level | Requirement |
|----|-------|-------------|
| N3.EV.1 | MUST | Every event carries the complete §2 envelope with the types of §2.1.1. |
| N3.EV.2 | MUST | `:event/id` is globally unique. |
| N3.EV.3 | MUST | No family redefines an envelope field's type or meaning (§2.1.1). |
| N3.EV.4 | MUST | Every event has exactly one scope per §2.3 and carries a non-nil value for that scope's key. |
| N3.EV.5 | MUST | `:event/sequence-number` is monotonic within scope (§2.2). |
| N3.EV.6 | MUST | Causally ordered: if B is caused by A, `sequence-number(B) > sequence-number(A)`. |
| N3.EV.7 | MUST | Replaying a scope's events in sequence order reproduces the same state (§2.2). |

#### Emission and registry

| ID | Level | Requirement |
|----|-------|-------------|
| N3.EM.1 | MUST | Emit at every point in §4.1. |
| N3.EM.2 | MUST | Only emit `:event/type` values registered in §6. |
| N3.EM.3 | MUST | §3, §4.1, and §6 agree on the set of event types (§6.1). |
| N3.EM.4 | MUST NOT | Exceed 3 status events per second per agent (§4.2). |
| N3.EM.5 | MUST | Emit at least one status event per 5 seconds during active work (§4.2). |
| N3.EM.6 | MUST | Event type keywords follow the naming rules of §6.2. |

#### Storage and retention

| ID | Level | Requirement |
|----|-------|-------------|
| N3.ST.1 | MUST | Events persist across process restarts (§4.3). |
| N3.ST.2 | MUST | Storage preserves sequence numbers and supports ordered replay (§4.3). |
| N3.ST.3 | MUST NOT | Expire an event before its retention-class minimum (§4.3.1). |
| N3.ST.4 | MUST NOT | Expire `:durable` or `:audit` events while a workflow in scope is non-terminal (§4.3.2). |
| N3.ST.5 | MUST | Expire whole prefixes only; never from the middle of a sequence (§4.3.2). |
| N3.ST.6 | MUST | Expose the oldest retained sequence number per scope (§4.3.2, §5.3.5). |

#### Streaming API

| ID | Level | Requirement |
|----|-------|-------------|
| N3.API.1 | MUST | Provide subscription by every scope key of §2.3 (§5.1). |
| N3.API.2 | MUST | Deliver dual-scoped events on both scopes, de-duplicated by `:event/id` (§5.1). |
| N3.API.3 | MUST | Provide the query API of §5.2, ordered by sequence ascending. |
| N3.API.4 | MUST | Provide an SSE endpoint per §5.3; WebSocket is OPTIONAL. |
| N3.API.5 | MUST | Authenticate per §5.3.2 and fail 401 in any network-exposed deployment. |
| N3.API.6 | MUST | Validate declared listener capability against RBAC; 403 on mismatch (§5.3.3). |
| N3.API.7 | MUST | Emit `listener/attached` first and `listener/detached` last on every stream (§5.3.3). |
| N3.API.8 | MUST | Evaluate subscription filters server-side; unfiltered events never cross the wire (§5.3.4). |
| N3.API.9 | MUST | Support resume by `?from-sequence=` and by `Last-Event-ID` (§5.3.5). |
| N3.API.10 | MUST | Respond 410 with `:oldest-available` when the requested sequence is out of retention (§5.3.5). |
| N3.API.11 | MUST | Apply one documented, deployment-consistent overflow policy (§5.3.6). |
| N3.API.12 | MUST | Emit an SSE heartbeat comment at least every 30 seconds (§5.3.7). |
| N3.API.13 | MUST | Enforce per-principal connection limits; 429 with `Retry-After` (§5.3.9). |

#### Compatibility

| ID | Level | Requirement |
|----|-------|-------------|
| N3.CP.1 | MUST | Version each event type's payload independently per §7.1. |
| N3.CP.2 | MUST | Classify and bump changes per §7.2. |
| N3.CP.3 | MUST NOT | Change a field's meaning in place (§7.2). |
| N3.CP.4 | MUST | Ignore unknown event types and continue (§7.3 rule 1). |
| N3.CP.5 | MUST | Ignore unknown fields on known types (§7.3 rule 2). |
| N3.CP.6 | MUST NOT | Silently process an event of a higher major version (§7.3 rule 4). |
| N3.CP.7 | MUST NOT | Let a skipped event desynchronize the consumer's sequence position (§7.3 rule 5). |
| N3.CP.8 | MUST | Keep `:event/type` stable across a major payload bump (§7.4). |

#### Sensitive data

| ID | Level | Requirement |
|----|-------|-------------|
| N3.SD.1 | MUST NOT | Emit any value in the §8.1 excluded set, in any field including free text. |
| N3.SD.2 | MUST | Redact at construction, not at delivery (§8.1). |
| N3.SD.3 | MUST | Substitute `"[REDACTED]"` rather than omitting the key (§8.2). |
| N3.SD.4 | MUST | Mark truncated fields with `"…[truncated]"` (§8.3). |
| N3.SD.5 | MUST NOT | Let stream truncation make an N6-required artifact unrecoverable (§8.3). |
| N3.SD.6 | MUST | Suppress every `:payload`-class field when `include-payloads=false` (§8.4). |
| N3.SD.7 | MUST | Suppress `:restricted` fields per-recipient at delivery (§8.4). |
| N3.SD.8 | MUST NOT | Treat archival as an exemption from redaction (§4.3.3, §8.5). |

#### Emission failure

| ID | Level | Requirement |
|----|-------|-------------|
| N3.EF.1 | MUST | Fail the triggering operation when a `:durable` or `:audit` emission cannot be recorded (§9.1). |
| N3.EF.2 | MUST NOT | Report workflow success on a stream missing its `:durable` events (§9.1). |
| N3.EF.3 | MUST | Count dropped `:ephemeral` / `:operational` events and reflect them in SLIs (§9.2). |
| N3.EF.4 | MUST NOT | Consume a sequence number for a failed emission (§9.3). |
| N3.EF.5 | MUST NOT | Let listener backpressure drop an event from storage or fail the triggering operation (§9.4). |

### 10.5 Test Obligations

A conformance suite MUST cover, at minimum:

1. **Envelope round-trip** — every registered event type serializes and
   deserializes without field loss, across both SSE JSON and the native
   representation (N3.EV.1).
2. **Registry agreement** — a mechanical check that §3, §4.1, and §6 enumerate
   the same set (N3.EM.3). This is the check most likely to catch spec drift
   before it reaches a consumer.
3. **Replay determinism** — a recorded workflow replays to an identical state
   (N3.EV.7).
4. **Resume correctness** — reconnect with `?from-sequence=` yields no gap and
   no duplicate across the catch-up boundary (N3.API.9).
5. **Retention-horizon behavior** — a request below the horizon returns 410
   with an accurate `:oldest-available` (N3.API.10, N3.ST.6).
6. **Forward compatibility** — a consumer built against version N processes a
   stream containing an unknown event type and an unknown field on a known type
   without error and without losing sequence position (N3.CP.4, N3.CP.5,
   N3.CP.7).
7. **Redaction** — a workflow whose tool arguments contain a secret produces a
   stream with `"[REDACTED]"` and no occurrence of the secret in any field
   (N3.SD.1, N3.SD.3).
8. **Fail-closed emission** — with the event sink forced to fail, an operation
   emitting a `:durable` or `:audit` event fails rather than succeeding
   silently (N3.EF.1, N3.EF.2).

---

## 11. Example Event Sequence

Complete event sequence for simple workflow:

```clojure
;; 1. Workflow starts
{:event/type :workflow/started
 :event/sequence-number 0
 :workflow/id #uuid "abc123"
 :message "Workflow started"}

;; 2. Planning phase starts
{:event/type :workflow/phase-started
 :event/sequence-number 1
 :workflow/id #uuid "abc123"
 :workflow/phase :plan
 :message "Planning phase started"}

;; 3. Planner agent starts
{:event/type :agent/started
 :event/sequence-number 2
 :workflow/id #uuid "abc123"
 :agent/id :planner
 :message "Planner agent started"}

;; 4. Planner reading spec
{:event/type :agent/status
 :event/sequence-number 3
 :agent/id :planner
 :status/type :reading
 :message "Reading spec file: specs/rds-import.edn"}

;; 5. Planner thinking
{:event/type :agent/status
 :event/sequence-number 4
 :agent/id :planner
 :status/type :thinking
 :message "Analyzing import constraints"}

;; 6. LLM request
{:event/type :llm/request
 :event/sequence-number 5
 :agent/id :planner
 :llm/model "claude-sonnet-4"
 :llm/prompt-tokens 2400
 :message "Calling Claude Sonnet (2.4k tokens)..."}

;; 7. LLM response
{:event/type :llm/response
 :event/sequence-number 6
 :agent/id :planner
 :llm/completion-tokens 850
 :llm/duration-ms 3200
 :message "Received response (850 tokens, 3.2s)"}

;; 8. Milestone
{:event/type :milestone/reached
 :event/sequence-number 7
 :agent/id :planner
 :milestone/id :plan-complete
 :message "Plan generation complete"}

;; 9. Planner completes
{:event/type :agent/completed
 :event/sequence-number 8
 :agent/id :planner
 :agent/duration-ms 45000
 :message "Planner agent completed"}

;; 10. Planning phase completes
{:event/type :workflow/phase-completed
 :event/sequence-number 9
 :workflow/phase :plan
 :phase/duration-ms 45000
 :message "Planning phase completed"}

;; ... Implementation phase ...
;; ... Verification phase ...
;; ... Review phase ...

;; N. Workflow completes
{:event/type :workflow/completed
 :event/sequence-number N
 :workflow/status :success
 :message "Workflow completed successfully"}
```

---

## 12. Rationale & Design Notes

### 12.1 Why Event Stream is Product Surface

Traditional logging is optimized for debugging after-the-fact. miniforge's event
stream is **real-time product infrastructure** because:

1. **UI depends on it** - TUI/Web render live progress from events
2. **Replay enables debugging** - Reproduce exact workflow state
3. **Analytics build on it** - Performance metrics, learning signals
4. **Compliance requires it** - Audit trail for SOCII/FedRAMP

### 12.2 Why Append-Only

Immutable events enable:

- Deterministic replay
- Distributed systems (eventual consistency)
- Audit compliance
- Debugging via time-travel

### 12.3 Why Per-Scope Sequencing

Total ordering per scope enables:

- UI to show coherent timeline
- Replay to be deterministic
- Causality reasoning (event A caused event B)

---

## 13. Future Extensions

### 13.1 Learning & Meta-Loop (Post-OSS)

Event stream will support:

- Signal extraction from events
- Pattern mining across workflows
- Heuristic A/B testing tracking

### 13.2 Distributed Coordination (Paid)

Event stream will extend to:

- Cross-workflow causality (train dependencies)
- Multi-user event attribution
- Fleet-wide event aggregation

---

## 14. References

- RFC 2119: Key words for use in RFCs to Indicate Requirement Levels
- N1 (Core Architecture): failure taxonomy (§5.3.3), SLIs (§5.5.2), repository
  intelligence (§2.27.9–2.27.10)
- N2 (Workflow Execution): Workflow engine emits lifecycle events
- N2-delta (Phase Checkpoint & Resume): checkpoint/resume event types (§3.21)
- N4 (Policy Packs): secret classification consumed by §8.1
- N5 (CLI/TUI/API): UI consumes event stream via subscription API
- N5-delta-supervisory-control-plane: supervisory entity shapes (§3.19)
- N5-delta-2 (PR Scoring), N5-delta-3 (Observational Entities), N5-delta-4
  (Automation Edge Correlator): additional supervisory family members (§3.19.1)
- N6 (Evidence & Provenance): Evidence bundles reference event streams
- N7 (Operational Policy Synthesis): OPSV event types (§3.14)
- N8 (Observability Control Interface): Listener/control action event types (§3.15)
- N9 (External PR Integration): Provider/PR/train event types (§3.16)
- I-DAG-ORCHESTRATION: DAG executor with PR lifecycle (Section 12: PR Lifecycle Events)

---

## Annex A — Implementation Conformance Status (informative)

This annex is **informative**. It records where the miniforge implementation
currently diverges from the contract above, as of 2026-08-05. It is not a
relaxation of any requirement in §1–§14: the spec is normative and the
implementation conforms to it, not the reverse.

The annex exists because divergences that are not written down are
indistinguishable from undiscovered ones. Each row is work, not an exemption.

### A.1 Name Divergences

The implementation emits a differently-named event than the spec requires.

| Spec (normative) | Implemented | Notes |
|------------------|-------------|-------|
| `repo-index/quality-computed` (§3.18) | `repo-index/quality-measured` | Field sets also differ: spec uses `:repo/id` + `:revision/commit-sha` + `:quality/*`; implementation uses `:index/id` + `:index/*`. N1 §2.27.9 independently specifies the spec name. |
| `repo-index/canary-failed` (§3.18) | `repo-index/coverage-changed` | Not a rename — a different event. The canary-recall contract of N1 §2.27.10 has no implementation. |
| `pr/opened` (§3.10) | `pr/created` | Both appear in the tree; `pr-lifecycle` emits `:pr/opened`, while the implementation's own registry resource (`event-type-registry.edn`) lists `pr/created`. The §6 registry in this spec is unambiguous: `pr/opened` is the contract. |
| `chain.edge/started` / `-completed` / `-failed` (§3.12) | `chain/started`, `chain/step-started`, and variants | Implementation models chain **steps**; the spec models chain **edges**. Reconciliation requires deciding which concept is canonical, then amending N1 and N3 together. |
| `tool/invoked` / `tool/completed` (§3.5) | also `agent/tool-call-started` / `tool/call-completed` | Two parallel tool-event vocabularies exist. §6.2 forbids the duplication; one MUST be withdrawn. |

### A.2 Specified, Not Implemented

Event types this spec requires that have no emission site:

- `subagent/spawned` (§3.4)
- `milestone/reached` (§3.8) — required by §4.1 point 8
- `task/claimed`, `task/capability-bound`, `task/scope-violation` (§3.13) —
  `task/scope-violation` is `:audit` class and carries the capability-enforcement
  evidence described in §9.1
- The N7 OPSV family (§3.14) in full
- The N9 external-PR family (§3.16) in full
- The Data Foundry family (§3.20) in full
- Pack lifecycle and Pack Run families (§3.11, §3.12) in full
- `workflow/cancelled` and the checkpoint/resume family (§3.21)

### A.3 Implemented, Not Specified

Event types emitted with no §3 schema and no §6 registry row. Under §6 these
are non-conformant emissions; each MUST either be added to §3 and §6 or
withdrawn.

`agent/chunk`, `agent/session-captured`, `agent/stream-stalled`,
`workflow/phase-heartbeat`, `workspace/persisted`, `zettel/promoted`,
`dependency/health-updated`, `dependency/recovered`, `pr/scored`,
`self-healing/workaround-applied`, `self-healing/backend-switched`,
`oci/container-started`, `oci/container-completed`,
`supervision/tool-use-evaluated`, `operator/intervention-anomaly`,
`pr-monitor/review-comments-arrived`, `pr-monitor/ci-failed`,
`standards-review/posted`, `control-plane/*`, `task/state-changed`,
`supervisory/spec-upserted`.

Several are plainly legitimate capabilities that were built without a
corresponding spec amendment — `workflow/phase-heartbeat` and
`agent/stream-stalled` in particular carry the stall-detection contract. The
resolution is an N3 amendment per §6.1, not silent acceptance.

### A.4 Structural

- **Dangling reference.** `components/pipeline-runner` cites "N3 §2.4 pipeline
  run statuses". No such section has ever existed in N3, and no spec defines
  PipelineRun statuses. The citation is unresolvable and MUST be corrected to
  point at whichever spec owns that vocabulary once one does.
- **Separate bus.** `pr-lifecycle` runs its own in-process event bus rather
  than the N3 stream. Events on it are not sequenced, retained, or replayable
  per §2.2 and §4.3. §3.10 assumes a single stream.
- **`:supervisory/schema-version`.** Already emitted by `supervisory-state`;
  §3.19 now requires it, so this row is closed on the next spec sync rather
  than being a code change.

---

**Version History:**

- 0.10.0-draft (2026-08-05): Spec-completion pass.
  **New normative sections:** event type registry (§6), schema evolution and
  consumer compatibility (§7), sensitive data and redaction (§8), emission
  failure semantics (§9), conformance requirement IDs and test obligations
  (§10.4–§10.5).
  **New event types:** workflow control and checkpoint family (§3.21 —
  `workflow/cancelled`, `workflow/checkpoint-written`,
  `workflow/checkpoint-write-failed`, `workflow/machine-snapshot-written`,
  `workflow/machine-snapshot-write-failed`, `workflow/resumed`,
  `workflow/spec-hash-mismatch`), sourced from N2 §5 and N2-delta §9;
  `listener/overflow` (§3.15), previously referenced by §5.3.6 but never
  defined; seven supervisory family members enumerated in §3.19.1.
  **Contract fixes:** `:pr/id` unified as the PR Work Item UUID across §3.10
  and §3.16, with `:pr/number` for provider-assigned numbers; bare
  `:timestamp` removed in favor of the envelope's `:event/timestamp`;
  `:event/sequence-number` unified on `long`; §2.3 generalized from
  PR-only to the full scope-key table (pack, repo, deployment scopes);
  `:supervisory/schema-version` required on the supervisory family;
  retention expanded from one line to four classes with replay-horizon rules
  (§4.3.1–§4.3.3); `:pr/id` subscription and query surfaces added to §5.1–§5.2
  as §2.3 already required.
  **Structural:** duplicate §3.17 resolved — Data Foundry renumbered to §3.20
  (Reliability keeps §3.17, which has existing inbound references); §2.2 and
  §2.3 restored to numeric order; §6–§9 inserted, former §6–§10 renumbered to
  §10–§14; stale N7/N8/N9 section cross-references in §14 corrected.
- 0.9.0-draft (2026-08-04): OPSV events now share a preallocated evidence-bundle
  identifier, canonical Experiment Pack/environment/verification/risk shapes,
  requested/effective actuation modes, and correlated N10 governed-effect records
- 0.8.0-draft (2026-04-23): Per-workflow streaming wire-contract amendments — §5.3
  expanded from a one-line SSE sketch to a complete contract for the per-workflow
  stream: authentication via bearer token (with browser-friendly query-param
  fallback), listener attach handshake aligned with N8 §2.1, server-side
  subscription filters, resume-from-sequence on reconnect, backpressure and
  buffer-overflow behavior, SSE wire format (event/id/data/retry + heartbeats),
  optional WebSocket wire format, rate limiting. Cross-workflow aggregation
  endpoints remain out of OSS scope
- 0.7.0-draft (2026-04-17): Added the §3.19 supervisory snapshot event family
  (`:supervisory/*-upserted`) for canonical N5 supervisory entities
- 0.6.0-draft (2026-03-08): Reliability Nines amendments — `:failure/class` enum on all
  failure events, reliability metric events (§3.17), repository intelligence events (§3.18)
- 0.5.0-draft (2026-02-16): Added pack lifecycle, Pack Run, capability denial, and chain
  edge events (§3.12); renumbered §3.13–§3.16
- 0.4.0-draft (2026-02-07): Added extension spec events from N7, N8, N9
  (§3.14–§3.16, §2.3 scope key)
- 0.3.0-draft (2026-02-04): Added task lifecycle events for DAG orchestration (§3.12)
- 0.2.0-draft (2026-02-03): Add PR lifecycle events for DAG orchestration (Section 3.10)
- 0.1.0-draft (2026-01-23): Initial event stream specification
