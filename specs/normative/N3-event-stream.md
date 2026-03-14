# N3 — Event Stream & Observability Contract

**Version:** 0.6.0-draft
**Date:** 2026-03-08
**Status:** Draft
**Conformance:** MUST

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
2. **Per-workflow sequencing** - Events for a workflow MUST be totally ordered
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
- **workflow/id** - MUST reference a valid workflow. MAY be nil for external PR events (N9); see §2.3.
- **event/sequence-number** - MUST be monotonically increasing per workflow (or per PR Work Item for external PR events)

### 2.3 Scope Key for Non-Workflow Events

Some event types originate outside a Miniforge workflow (e.g., external PR state changes
from N9). For these events:

- `:workflow/id` MAY be nil.
- Events MUST instead include `:pr/id` (the PR Work Item UUID) as a correlation key.
- Implementations MUST support subscribing to events by `:pr/id` in addition to `:workflow/id`.
- Events MUST be ordered per PR Work Item when no workflow scope exists.
- For Miniforge-originated PRs, `:workflow/id` MUST reference the originating workflow AND
  `:pr/id` MAY also be present for cross-referencing.

### 2.2 Ordering Guarantees

Implementations MUST provide:

1. **Total order per workflow** - Events for a workflow MUST be sequenced
2. **Causal ordering** - If event B caused by event A, `sequence-number(B) > sequence-number(A)`
3. **Replay determinism** - Replaying events in sequence order MUST produce same workflow state

---

## 3. Required Event Types

Implementations MUST emit these event types:

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
 :phase/outcome :success         ; :success, :failure, :skipped
 :phase/artifacts [uuid ...]     ; Artifacts produced

 :message "Implementation phase completed"}
```

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
 :from-agent/instance-id uuid
 :to-agent/id :planner
 :workflow/id uuid

 :message-type :clarification-request
 :message-content string

 :message "Asking Planner: Should we create new security group?"}
```

#### agent/message-received

```clojure
{:event/type :agent/message-received
 :from-agent/id :planner
 :to-agent/id :implementer
 :workflow/id uuid

 :message-type :clarification-response
 :message-content string

 :message "Planner response: Reuse existing security group sg-prod-rds"}
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

All PR lifecycle events MUST include these correlation fields:

```clojure
{:dag/id uuid                    ; REQUIRED: DAG run ID
 :run/id uuid                    ; REQUIRED: Run instance ID
 :plan/id uuid                   ; OPTIONAL: Plan ID (if applicable)
 :task/id uuid                   ; REQUIRED: Task workflow ID
 :pr/id string                   ; REQUIRED: PR number/identifier
 :pr/url string                  ; OPTIONAL: Full PR URL
 :sha string                     ; REQUIRED: Commit SHA
 :timestamp inst}                ; REQUIRED: Event timestamp
```

#### pr/opened

```clojure
{:event/type :pr/opened
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id string
 :pr/url string
 :pr/branch string
 :pr/base-sha string
 :sha string
 :timestamp inst

 :message "PR #123 opened for task"}
```

#### pr/ci-passed

```clojure
{:event/type :pr/ci-passed
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id string
 :sha string
 :ci/checks [{:name string :status :success :duration-ms long}]
 :timestamp inst

 :message "CI passed for PR #123"}
```

#### pr/ci-failed

```clojure
{:event/type :pr/ci-failed
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id string
 :sha string
 :ci/checks [{:name string :status :failure :output string}]
 :ci/failure-summary string
 :timestamp inst

 :message "CI failed for PR #123: 2 tests failing"}
```

#### pr/review-approved

```clojure
{:event/type :pr/review-approved
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id string
 :sha string
 :review/approvers [string ...]
 :review/approval-count long
 :timestamp inst

 :message "PR #123 approved by alice, bob"}
```

#### pr/review-changes-requested

```clojure
{:event/type :pr/review-changes-requested
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id string
 :sha string
 :review/requesters [string ...]
 :review/comments [{:file string :line long :body string}]
 :timestamp inst

 :message "Changes requested on PR #123"}
```

#### pr/comment-actionable

```clojure
{:event/type :pr/comment-actionable
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id string
 :sha string
 :comment/id string
 :comment/author string
 :comment/body string
 :comment/file string            ; OPTIONAL: if inline comment
 :comment/line long              ; OPTIONAL: if inline comment
 :comment/classification keyword ; :code-change, :bug-report, :test-failure, :constraint-violation
 :timestamp inst

 :message "Actionable comment on PR #123: fix null check"}
```

#### pr/fix-pushed

```clojure
{:event/type :pr/fix-pushed
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id string
 :sha string                     ; New commit SHA
 :fix/type keyword               ; :ci-failure, :review-changes, :conflict
 :fix/iteration long             ; Fix attempt number
 :fix/files-modified [string ...]
 :timestamp inst

 :message "Fix pushed for PR #123 (attempt 2)"}
```

#### pr/merged

```clojure
{:event/type :pr/merged
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id string
 :sha string                     ; Merge commit SHA
 :pr/merge-method keyword        ; :merge, :squash, :rebase
 :timestamp inst

 :message "PR #123 merged"}
```

#### pr/closed

```clojure
{:event/type :pr/closed
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :pr/id string
 :sha string
 :close/reason keyword           ; :abandoned, :superseded, :failed
 :timestamp inst

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

All task lifecycle events MUST include:

```clojure
{:dag/id uuid                    ; REQUIRED: DAG run ID
 :run/id uuid                    ; REQUIRED: Run instance ID
 :task/id uuid                   ; REQUIRED: Task ID
 :timestamp inst}                ; REQUIRED: Event timestamp
```

#### task/frontier-entered

Emitted when a task's dependencies are all satisfied and it becomes eligible for dispatch.

```clojure
{:event/type :task/frontier-entered
 :dag/id uuid
 :run/id uuid
 :task/id uuid
 :frontier/size long             ; Current frontier size after this task entered
 :frontier/trigger-task uuid     ; Task whose terminal state caused this entry
 :timestamp inst

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
 :timestamp inst

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
 :timestamp inst

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
 :timestamp inst

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
 :timestamp inst

 :message "Task skipped: dependency task-abc failed"}
```

### 3.14 OPSV Events (N7)

For Operational Policy Synthesis workflows (see N7), implementations MUST emit these
event types:

#### opsv.experiment/planned

```clojure
{:event/type :opsv.experiment/planned
 :workflow/id uuid
 :opsv/pack-hash string              ; Experiment Pack content hash
 :opsv/targets {:services [...] :environments [...]}
 :opsv/risk-score {:level keyword :factors [...]}
 :message "OPSV experiment planned: {pack-hash}"}
```

#### opsv.experiment/started

```clojure
{:event/type :opsv.experiment/started
 :workflow/id uuid
 :opsv/pack-hash string
 :opsv/environment-fingerprint {...} ; Cluster, node pool, image digests, config
 :message "OPSV experiment started in {environment}"}
```

#### opsv/load-step

```clojure
{:event/type :opsv/load-step
 :workflow/id uuid
 :opsv/step-id string
 :opsv/intended-load {...}
 :opsv/observed-load {...}
 :message "OPSV load step {step-id}: {intended} → {observed}"}
```

#### opsv.guardrail/abort

```clojure
{:event/type :opsv.guardrail/abort
 :workflow/id uuid
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
 :opsv/iteration-id string
 :opsv/params {...}
 :opsv/observed-metrics-summary {...}
 :message "OPSV convergence iteration {iteration-id}"}
```

#### opsv.policy/proposed

```clojure
{:event/type :opsv.policy/proposed
 :workflow/id uuid
 :opsv/policy-hash string
 :opsv/diff-refs [uuid ...]          ; N6 artifact references
 :opsv/confidence keyword
 :message "OPSV policy proposed: {policy-hash}"}
```

#### opsv.verification/result

```clojure
{:event/type :opsv.verification/result
 :workflow/id uuid
 :opsv/passed? boolean
 :opsv/criteria-evaluation [...]
 :opsv/evidence-bundle-id uuid
 :message "OPSV verification {passed?}: {summary}"}
```

#### opsv.actuation/emitted

```clojure
{:event/type :opsv.actuation/emitted
 :workflow/id uuid
 :opsv/actuation-mode keyword        ; :pr-only or :apply-allowed
 :opsv/pr-refs [string ...]          ; PR URLs if PR_ONLY
 :opsv/apply-refs [string ...]       ; Applied resource refs if APPLY_ALLOWED
 :message "OPSV actuation emitted: {mode}"}
```

#### opsv.drift/detected

```clojure
{:event/type :opsv.drift/detected
 :workflow/id uuid                   ; OPTIONAL: may be nil if detected by monitoring
 :opsv/signal keyword
 :opsv/deviation {...}
 :opsv/suggested-rerun? boolean
 :message "OPSV drift detected: {signal}"}
```

All OPSV events MUST link to the corresponding evidence bundle id per N6.

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

### 3.17 Data Foundry Events

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
4. **Retention policy** - Status events MAY expire (suggest 24h), lifecycle/gate events MUST be retained

---

## 5. Event Stream API

### 5.1 Subscription Protocol

Implementations MUST provide:

```clojure
;; Subscribe to workflow events
(subscribe-to-workflow workflow-id callback-fn)
;; Returns: subscription handle

;; Subscribe to all fleet events
(subscribe-to-fleet callback-fn)
;; Returns: subscription handle

;; Unsubscribe
(unsubscribe subscription-handle)
```

### 5.2 Query API

Implementations MUST support:

```clojure
;; Get events for workflow
(get-events workflow-id {:offset long :limit long})
;; Returns: sequence of events

;; Get events by type
(get-events-by-type workflow-id event-type {:offset long :limit long})

;; Get latest status
(get-latest-status workflow-id agent-id)
;; Returns: most recent agent/status event
```

### 5.3 Streaming Endpoints (HTTP)

Implementations MUST provide Server-Sent Events (SSE) endpoint:

```http
GET /api/workflows/:id/stream
```

Response format:

```text
event: agent-status
data: {"event/type":"agent/status","workflow/id":"...","message":"..."}

event: milestone-reached
data: {"event/type":"milestone/reached","workflow/id":"...","milestone/id":"code-generated"}
```

Implementations MAY provide WebSocket endpoint as alternative.

---

## 6. Conformance & Testing

### 6.1 Schema Validation

Implementations MUST:

1. Validate all emitted events against schema
2. Reject invalid events with clear error messages
3. Log validation failures

### 6.2 Event Replay Tests

Conformance tests MUST verify:

1. Replaying events reproduces workflow state
2. Sequence numbers are monotonic
3. Causal ordering is preserved
4. All required event types are emitted

### 6.3 Performance Requirements

Implementations MUST:

- Emit events with <10ms latency (p99)
- Support ≥100 subscriptions per workflow
- Stream events to clients with <100ms latency (p99)

---

## 7. Example Event Sequence

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

## 8. Rationale & Design Notes

### 8.1 Why Event Stream is Product Surface

Traditional logging is optimized for debugging after-the-fact. miniforge's event
stream is **real-time product infrastructure** because:

1. **UI depends on it** - TUI/Web render live progress from events
2. **Replay enables debugging** - Reproduce exact workflow state
3. **Analytics build on it** - Performance metrics, learning signals
4. **Compliance requires it** - Audit trail for SOCII/FedRAMP

### 8.2 Why Append-Only

Immutable events enable:

- Deterministic replay
- Distributed systems (eventual consistency)
- Audit compliance
- Debugging via time-travel

### 8.3 Why Per-Workflow Sequencing

Total ordering per workflow enables:

- UI to show coherent timeline
- Replay to be deterministic
- Causality reasoning (event A caused event B)

---

## 9. Future Extensions

### 9.1 Learning & Meta-Loop (Post-OSS)

Event stream will support:

- Signal extraction from events
- Pattern mining across workflows
- Heuristic A/B testing tracking

### 9.2 Distributed Coordination (Paid)

Event stream will extend to:

- Cross-workflow causality (train dependencies)
- Multi-user event attribution
- Fleet-wide event aggregation

---

## 10. References

- RFC 2119: Key words for use in RFCs to Indicate Requirement Levels
- N2 (Workflow Execution): Workflow engine emits lifecycle events
- N5 (CLI/TUI/API): UI consumes event stream via subscription API
- N6 (Evidence & Provenance): Evidence bundles reference event streams
- N7 (Operational Policy Synthesis): OPSV event types (§3.13)
- N8 (Observability Control Interface): Listener/control action event types (§3.14)
- N9 (External PR Integration): Provider/PR/train event types (§3.15)
- I-DAG-ORCHESTRATION: DAG executor with PR lifecycle (Section 12: PR Lifecycle Events)

---

**Version History:**

- 0.6.0-draft (2026-03-08): Reliability Nines amendments — `:failure/class` enum on all
  failure events, reliability metric events (§3.17), repository intelligence events (§3.18)
- 0.5.0-draft (2026-02-16): Added pack lifecycle, Pack Run, capability denial, and chain
  edge events (§3.12); renumbered §3.13–§3.16
- 0.4.0-draft (2026-02-07): Added extension spec events from N7, N8, N9
  (§3.14–§3.16, §2.3 scope key)
- 0.3.0-draft (2026-02-04): Added task lifecycle events for DAG orchestration (§3.12)
- 0.2.0-draft (2026-02-03): Add PR lifecycle events for DAG orchestration (Section 3.10)
- 0.1.0-draft (2026-01-23): Initial event stream specification
