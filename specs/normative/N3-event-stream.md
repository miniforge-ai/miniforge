# N3 — Event Stream & Observability Contract

**Version:** 0.1.0-draft
**Date:** 2026-01-23
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
- **workflow/id** - MUST reference a valid workflow
- **event/sequence-number** - MUST be monotonically increasing per workflow

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
 :workflow/failure-reason string
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

 :agent/failure-reason string
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
 :etl/failure-reason string
 :etl/error-details {...}             ; OPTIONAL: structured error information

 :message "ETL workflow failed: {reason}"}
```

ETL workflows MUST emit this event if any critical failure prevents completion.
Implementations SHOULD include enough detail in `:etl/error-details` to enable
debugging without log diving.

### 3.12 Task Lifecycle Events (DAG Orchestration)

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
- N6 (Evidence & Provenance): Evidence bundles reference event streams
- N5 (CLI/TUI/API): UI consumes event stream via subscription API
- N2 (Workflow Execution): Workflow engine emits lifecycle events
- I-DAG-ORCHESTRATION: DAG executor with PR lifecycle (Section 12: PR Lifecycle Events)

---

**Version History:**

- 0.3.0-draft (2026-02-04): Added task lifecycle events for DAG orchestration (§3.12)
- 0.2.0-draft (2026-02-03): Add PR lifecycle events for DAG orchestration (Section 3.10)
- 0.1.0-draft (2026-01-23): Initial event stream specification
