<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N8 — Observability Control Interface

**Version:** 0.4.0-draft
**Date:** 2026-03-08
**Status:** Draft
**Conformance:** MUST

---

## 0. Status and Scope

### 0.1 Purpose

This specification defines the normative requirements for the **Observability Control
Interface** (OCI): a capability that enables external actors to listen to workflow execution,
advise on decisions, and (when authorized) control workflow behavior through a governed
command surface.

The OCI transforms the event stream (N3) from a passive observability layer into an active
**control plane** that supports:

- Local watcher agents monitoring and advising on workflow execution
- Fleet dashboards with operational control (pause, rollback, quarantine)
- Enterprise fleet listeners detecting patterns across organizations
- Meta-meta monitoring loops for organizational learning

### 0.2 Relationship to N1–N7

OCI is an extension of existing Miniforge normative contracts:

- **N1**: introduces new *concepts* (Listener, Capability Level, Control Action,
          Advisory Annotation) as specializations of Agent/Observer/Policy.
          Now landed in N1 §2.15–§2.18 and §12 (glossary).
- **N3**: defines listener subscription mechanics, privacy hooks, and OTel interoperability
          as extensions to the event stream contract.
          Now landed in N3 §3.14 (Observability Control Interface Events).
- **N4**: defines RBAC rules and policy gates governing control actions.
          Now landed in N4 §5.1.6 (Control Action Governance).
- **N5**: defines control-plane commands for CLI/TUI/API.
          Now landed in N5 §2.3.3 (Listener and Control Commands) and §3.2.7 (Listener and Control Panel).
- **N6**: defines audit requirements for control actions in evidence bundles.
          Now landed in N6 §2.9 (Control Action Evidence) and §3.1.1 (artifact types).
- **N7**: OCI provides the listener infrastructure that OPSV uses for experiment monitoring.

### 0.3 Non-goals

OCI SHALL NOT:

- replace the event stream (N3); it extends and consumes it.
- bypass policy gates (N4); all control actions are subject to governance.
- define new agent types; listeners use existing agent protocols (N1).

### 0.4 Design Rationale

Industry observability platforms (Langfuse, Datadog LLM Observability) provide tracing and
dashboards. Miniforge differentiates by coupling observability to **governed actuation**:

- Listeners can observe, advise, AND control (subject to RBAC and gates)
- Control actions produce durable artifacts and policy updates
- Org-level patterns feed back into policy synthesis (N7)

This positions Miniforge as an "SDLC control plane for agent fleets" rather than
"LLM observability."

---

## 1. Terminology

### 1.1 Listener

A **Listener** is an external actor that subscribes to workflow events and MAY interact
with workflow execution. Listeners include:

- Watcher agents (autonomous monitors)
- Dashboard sessions (human operators)
- Fleet aggregators (cross-workflow monitors)
- Enterprise collectors (org-level monitors)

### 1.2 Capability Level

Listeners operate at one of three **Capability Levels**:

| Level     | Permissions                                     | Use Case                          |
| --------- | ----------------------------------------------- | --------------------------------- |
| `OBSERVE` | Read-only event stream access                   | Monitoring, analytics, audit      |
| `ADVISE`  | Emit advisory annotations (non-blocking)        | Recommendations, warnings         |
| `CONTROL` | Request control actions (subject to gates)      | Pause, rollback, approve, adjust  |

### 1.3 Control Action

A **Control Action** is a command that modifies workflow execution state. All control
actions:

- MUST be authorized by RBAC
- MUST pass through policy gates
- MUST be audit-logged
- MAY require multi-party approval for high-risk operations

### 1.4 Advisory Annotation

An **Advisory Annotation** is a non-blocking message attached to a workflow or event.
Annotations:

- MUST NOT block workflow execution
- MAY be surfaced in UI
- MAY trigger alerts if patterns match

---

## 2. Listener Capability Model

### 2.1 Capability Registration

Listeners MUST register with a declared capability level:

```clojure
{:listener/id uuid                    ; REQUIRED: unique listener identifier
 :listener/type keyword               ; REQUIRED: :watcher | :dashboard | :fleet | :enterprise
 :listener/capability keyword         ; REQUIRED: :observe | :advise | :control

 :listener/identity                   ; REQUIRED for ADVISE/CONTROL
 {:principal string                   ; User or service account
  :credentials {...}                  ; Auth credentials (opaque)
  :roles [keyword ...]}               ; RBAC roles

 :listener/filters                    ; OPTIONAL: event filtering
 {:workflow-ids [uuid ...]            ; Subscribe to specific workflows
  :event-types [keyword ...]          ; Filter by event type
  :phases [keyword ...]               ; Filter by workflow phase
  :agents [keyword ...]}              ; Filter by agent

 :listener/options
 {:buffer-size int                    ; Event buffer size (default 1000)
  :include-payloads? boolean          ; Include full event payloads (default false)
  :sampling-rate float}}              ; Sampling rate 0.0-1.0 (default 1.0)
```

### 2.2 Capability Enforcement

Implementations MUST enforce capability levels:

**OBSERVE capability:**

- MAY subscribe to event streams
- MAY query event history
- MUST NOT emit annotations or control actions

**ADVISE capability:**

- Includes all OBSERVE permissions
- MAY emit advisory annotations
- MUST NOT emit control actions

**CONTROL capability:**

- Includes all ADVISE permissions
- MAY request control actions (subject to gates)
- Control actions MUST pass RBAC and policy gates

### 2.3 RBAC Requirements

CONTROL capability MUST integrate with RBAC:

```clojure
{:rbac/role keyword                   ; Role identifier
 :rbac/permissions
 {:workflows                          ; Workflow-level permissions
  {:pause boolean
   :resume boolean
   :retry boolean
   :cancel boolean}

  :agents                             ; Agent-level permissions
  {:quarantine boolean
   :adjust-budget boolean}

  :fleet                              ; Fleet-level permissions
  {:emergency-stop boolean
   :policy-override boolean}

  :approvals                          ; Approval permissions
  {:gate-override boolean
   :budget-escalation boolean}}

 :rbac/constraints
 {:workflow-patterns [string ...]     ; Glob patterns for allowed workflows
  :time-windows [{:start :end} ...]   ; Allowed operation windows
  :require-mfa? boolean}}             ; Require MFA for control actions
```

---

## 3. Control Action Surface

### 3.1 Required Control Actions

Implementations MUST support these control actions:

#### 3.1.1 Workflow Control

| Action          | Description                                   | Risk Level |
| --------------- | --------------------------------------------- | ---------- |
| `pause`         | Pause workflow execution                      | Low        |
| `resume`        | Resume paused workflow                        | Low        |
| `retry`         | Retry current phase with optional context     | Medium     |
| `rollback`      | Rollback to previous checkpoint               | Medium     |
| `cancel`        | Cancel workflow execution                     | Medium     |
| `force-complete`| Force workflow to completed state (dangerous) | Critical   |

#### 3.1.2 Agent Control

| Action           | Description                                  | Risk Level |
| ---------------- | -------------------------------------------- | ---------- |
| `quarantine`     | Disable agent for current workflow           | Medium     |
| `quarantine-fleet`| Disable agent for all fleet workflows       | High       |
| `adjust-budget`  | Modify token/time budget for agent           | Medium     |
| `inject-context` | Add context to agent's next iteration        | Medium     |

#### 3.1.3 Gate Control

| Action           | Description                                  | Risk Level |
| ---------------- | -------------------------------------------- | ---------- |
| `approve`        | Approve pending gate check                   | Medium     |
| `reject`         | Reject pending gate check                    | Medium     |
| `override`       | Override gate failure (requires justification)| High      |
| `defer`          | Defer gate check to later phase              | Medium     |

#### 3.1.4 Fleet Control

| Action           | Description                                  | Risk Level |
| ---------------- | -------------------------------------------- | ---------- |
| `emergency-stop` | Halt all fleet workflows                     | Critical   |
| `drain`          | Stop accepting new workflows, complete existing| High     |
| `scale`          | Adjust fleet concurrency limits              | Medium     |

#### 3.1.5 Checkpoint Control

Workflow Packs (N1 §2.24) and chained workflows (N2 §14) MAY declare checkpoints
where execution pauses pending explicit approval. Checkpoint control actions:

| Action              | Description                                          | Risk Level |
| ------------------- | ---------------------------------------------------- | ---------- |
| `checkpoint.request`| Request a checkpoint at the next eligible boundary   | Low        |
| `checkpoint.approve`| Approve a pending checkpoint and resume              | Medium     |
| `checkpoint.reject` | Reject a pending checkpoint and halt the workflow    | Medium     |

`checkpoint.request` parameters:

```clojure
{:action/type :checkpoint.request
 :action/target {:target-type :workflow | :chain-edge
                 :target-id uuid}
 :action/parameters
 {:checkpoint/at keyword        ; :next-phase | :next-chain-edge | :pre-release
  :checkpoint/reason string     ; OPTIONAL: operator justification
  :checkpoint/expires-at inst}} ; OPTIONAL: auto-approve if not rejected
```

When a checkpoint is reached, implementations MUST:

1. Emit `checkpoint/reached` event (§10) with the pending action id
2. Transition the workflow to `:paused-awaiting-checkpoint`
3. Wait for `checkpoint.approve` or `checkpoint.reject`
4. If `:checkpoint/expires-at` elapses without a decision, emit
   `checkpoint/expired` and treat as reject unless policy specifies otherwise

Checkpoint approval MAY require multi-party approval per §3.3.

#### 3.1.6 Model Control

When policy permits, operators MAY override the LLM model used by an agent
for a scoped run. Model control actions:

| Action          | Description                                              | Risk Level |
| --------------- | -------------------------------------------------------- | ---------- |
| `model.override`| Override model for the CURRENT phase or call only        | High       |
| `model.set`     | Override model for the remainder of the workflow         | High       |
| `model.clear`   | Revert any active override; fall back to workflow default| Medium     |

`model.override` and `model.set` parameters:

```clojure
{:action/type :model.override | :model.set
 :action/target {:target-type :agent | :workflow
                 :target-id uuid}
 :action/parameters
 {:model/provider keyword             ; :anthropic | :openai | :local | ...
  :model/id string                    ; provider-specific model id
  :model/reason string                ; REQUIRED: justification
  :model/expires-at inst              ; REQUIRED for :model.set; MAY be omitted for :override
  :model/scope #{:current-phase :current-workflow :current-call}
  :model/fallback-allowed? boolean}}  ; default false — fail if override model unavailable
```

Implementations MUST:

1. Verify the target model is in the deployment's allowed-models list per
   policy (N4); reject if not
2. Require justification text; actions with empty `:model/reason` MUST fail
3. Record the pre-override model and the override in Pack Run evidence
   (N6 §2.11.1) and in `:evidence/control-actions` (§11.1)
4. Emit `model/overridden` event (§10) with `:model/provider`, `:model/id`,
   and scope; redact any provider credentials
5. Enforce `:model/expires-at` — on expiration, automatically emit
   `model.clear` and a `model/override-expired` event
6. If `:model/fallback-allowed?` is false and the override model becomes
   unavailable mid-run, the workflow MUST fail rather than silently
   falling back

Model overrides MUST NOT persist across workflow boundaries. A `model.set`
that outlives a workflow is a bug.

### 3.2 Control Action Schema

All control actions MUST conform to this schema:

```clojure
{:action/id uuid                      ; REQUIRED: unique action identifier
 :action/type keyword                 ; REQUIRED: action type from §3.1
 :action/timestamp inst               ; REQUIRED: when action was requested

 :action/target                       ; REQUIRED: what the action applies to
 {:target-type keyword                ; :workflow | :agent | :gate | :fleet
  :target-id uuid                     ; Target identifier
  :target-context {...}}              ; Additional context

 :action/requester                    ; REQUIRED: who requested the action
 {:principal string
  :capability keyword                 ; Must be :control
  :listener-id uuid}

 :action/justification string         ; REQUIRED for High/Critical risk

 :action/parameters {...}             ; OPTIONAL: action-specific parameters

 :action/approval                     ; REQUIRED for High/Critical if multi-party required
 {:required-approvers int
  :approvers [{:principal :timestamp :decision} ...]
  :approval-status keyword}           ; :pending | :approved | :rejected

 :action/result                       ; Populated after execution
 {:status keyword                     ; :success | :failure | :pending
  :executed-at inst
  :error {...}}}                      ; If failed
```

### 3.3 Multi-Party Approval

Control actions with risk level High or Critical MAY require multi-party approval:

```clojure
{:approval/policy
 {:min-approvers int                  ; Minimum approvers required
  :timeout-ms long                    ; Approval timeout
  :escalation-path [string ...]       ; Escalation chain
  :auto-reject-on-timeout? boolean}   ; Auto-reject if timeout

 :approval/rules
 [{:action-type keyword               ; Which actions require approval
   :risk-level keyword                ; Minimum risk level
   :require-different-principal? boolean}]}  ; Requester cannot approve
```

### 3.4 Safe-Mode Posture

Safe-mode is a system-wide autonomy demotion designed to prevent cascading failures
during reliability incidents. It is the operational response mechanism for degradation
mode `:safe-mode` (N1 §5.5.5).

#### 3.4.1 Safe-Mode Triggers

Safe-mode MUST be triggerable by:

1. **Error budget exhaustion** — Any `:critical` tier SLI error budget reaching 0.0
   (see N1 §5.5.4). Implementations SHOULD trigger safe-mode automatically.
2. **Emergency stop** — The `emergency-stop` control action (§3.1). This is always
   a manual trigger.
3. **Consecutive unknown failures** — Configurable threshold (default: 3) of consecutive
   failures classified as `:failure.class/unknown` (N1 §5.3.3). Indicates insufficient
   failure instrumentation and warrants caution.
4. **Manual operator request** — Via CLI: `mf fleet safe-mode enter --reason "..."`

#### 3.4.2 Safe-Mode Behavior

When safe-mode is active, implementations MUST:

1. **Demote autonomy** — All subsystem autonomy levels MUST be demoted to A0 (Observe)
   per N1 §5.6. No autonomous write actions are permitted.
2. **Queue new workflows** — New workflow submissions MUST be queued, not executed.
   Queued workflows MUST be persisted and released when safe-mode exits.
3. **Pause in-flight workflows** — In-flight workflows MUST be paused at the next phase
   boundary. Paused workflows MUST be resumable when safe-mode exits.
4. **Open circuit breakers** — All circuit breakers for Class B+ tools (N10 §3.5)
   MUST be forced to `:open`.
5. **Emit event** — A `:safe-mode/entered` event MUST be emitted (see §3.4.4).
6. **Display indicator** — The TUI/CLI MUST display a prominent safe-mode indicator
   (see N5 extensions).

#### 3.4.3 Safe-Mode Exit

Exiting safe-mode MUST require:

1. **Explicit operator action** — `mf fleet safe-mode exit --reason "..."` or equivalent
   API call. Safe-mode MUST NOT exit automatically.
2. **Justification** — A human-readable justification string is REQUIRED.
3. **Event emission** — A `:safe-mode/exited` event MUST be emitted with justification,
   duration, and exit principal.
4. **Autonomy restoration** — Autonomy levels MUST be restored to their pre-safe-mode
   values. If pre-safe-mode state is unavailable, autonomy MUST default to A1 (Recommend).
5. **Queue release** — Queued workflows MUST be released for execution.
6. **Circuit breaker reset** — Forced-open circuit breakers MUST be reset to `:half-open`
   to re-probe tool health.

#### 3.4.4 Safe-Mode State and Events

```clojure
;; Safe-Mode State
{:safe-mode/active?              boolean  ; REQUIRED
 :safe-mode/entered-at           inst     ; REQUIRED when active
 :safe-mode/trigger              keyword  ; REQUIRED: :error-budget | :emergency-stop
                                          ;           :unknown-failures | :manual
 :safe-mode/trigger-details      string   ; OPTIONAL: additional context
 :safe-mode/pre-autonomy-levels  map      ; REQUIRED: {subsystem-key -> previous-level}
 :safe-mode/queued-workflow-ids  [uuid]}  ; OPTIONAL: workflows queued during safe-mode
```

```clojure
;; safe-mode/entered event (emitted to N3)
{:event/type :safe-mode/entered
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :safe-mode/trigger keyword
 :safe-mode/trigger-details string
 :message "Safe-mode entered: {trigger}"}

;; safe-mode/exited event (emitted to N3)
{:event/type :safe-mode/exited
 :event/id uuid
 :event/timestamp inst
 :event/version "1.0.0"
 :safe-mode/exited-by string             ; Principal who exited safe-mode
 :safe-mode/justification string         ; REQUIRED: why safe-mode was exited
 :safe-mode/duration-ms long             ; How long safe-mode was active
 :safe-mode/workflows-queued long        ; Number of workflows that were queued
 :message "Safe-mode exited after {duration}: {justification}"}
```

---

## 4. Advisory Annotation System

### 4.1 Annotation Schema

Advisory annotations MUST conform to this schema:

```clojure
{:annotation/id uuid                  ; REQUIRED: unique annotation identifier
 :annotation/timestamp inst           ; REQUIRED: when annotation was created
 :annotation/type keyword             ; REQUIRED: :recommendation | :warning | :insight | :question

 :annotation/source                   ; REQUIRED: who created the annotation
 {:listener-id uuid
  :principal string
  :capability keyword}                ; Must be :advise or :control

 :annotation/target                   ; REQUIRED: what the annotation is about
 {:workflow-id uuid
  :event-id uuid                      ; OPTIONAL: specific event
  :phase keyword                      ; OPTIONAL: specific phase
  :agent-id keyword}                  ; OPTIONAL: specific agent

 :annotation/content
 {:title string                       ; REQUIRED: short summary
  :body string                        ; REQUIRED: detailed message
  :severity keyword                   ; OPTIONAL: :info | :warning | :error
  :suggested-action keyword           ; OPTIONAL: recommended control action
  :metadata {...}}                    ; OPTIONAL: structured data

 :annotation/visibility keyword}      ; :private | :team | :fleet | :public
```

### 4.2 Annotation Delivery

Annotations MUST be:

1. **Attached to target** - Annotations appear with their target in UI
2. **Streamed to subscribers** - Listeners receive annotations as events
3. **Stored in evidence** - Annotations persist in evidence bundles (N6)
4. **Queryable** - Annotations can be retrieved by target

### 4.3 Annotation Events

Annotations MUST emit events per N3:

```clojure
{:event/type :annotation/created
 :workflow/id uuid
 :annotation/id uuid
 :annotation/type keyword
 :annotation/source {...}
 :message "Advisory annotation: {title}"}
```

---

## 5. Privacy and Redaction

**N3 owns redaction and retention.** N3 §8 defines what may never be emitted,
the `"[REDACTED]"` marker, truncation, and the three field classes; N3 §4.3
defines the four retention classes and their minimums. This section defines
only what is specific to *listeners* — which principal sees which class — and
MUST NOT restate or vary those contracts.

Earlier revisions of this section carried a parallel model: privacy levels
`metadata-only | redacted | full`, a `:redaction/patterns` regex table, a
`:redaction/field-rules` vocabulary of `:include | :redact | :exclude`, and a
`:retention/policies` schema with its own day counts. All are withdrawn. Four
overlapping vocabularies for two concerns meant an operator configuring
redaction here had no way to know whether N3 §8's MUST NOT still applied — it
does, unconditionally, and it is not configurable.

### 5.1 Per-Listener Content Visibility

What a listener receives is determined by N3 §8.4's field classes and the
principal's RBAC role (§2.3):

| Field class | Delivered to |
|-------------|--------------|
| `:public` | Every attached listener |
| `:payload` | Listeners that have not set `include-payloads=false` (N3 §5.3.4) |
| `:restricted` | Only principals whose RBAC role permits that class |

`:restricted` suppression is **per-recipient at delivery**, not per-event at
emission (N3 §8.4): two listeners on one stream may be entitled to different
views of the same event.

A deployment MAY configure a listener type's default — for instance that
`:fleet` listeners default to `include-payloads=false`. It MUST NOT configure
away N3 §8.1: no configuration, listener type, or RBAC role causes a
never-emitted value to be emitted, because that value was never in the event
(N3 §8.1 redacts at construction).

### 5.2 Redaction Configuration

Redaction patterns are **configuration, not code**. Per
`standards/miniforge/foundations/config-as-data` (dewey 007), the pattern set
lives in an EDN resource with a schema; implementations MUST NOT accept a
function as a configuration value.

An earlier revision specified `:redaction/custom-fn function`. That is
withdrawn — a function in a config map cannot be serialized, diffed, reviewed,
or audited, which defeats the purpose of a redaction policy an auditor needs to
inspect.

Where a deployment needs a detection rule beyond N3 §8.1's set, it adds a
pattern to that configuration. Implementations MUST apply deployment patterns
in addition to N3 §8.1's excluded set, never instead of it.

### 5.3 Retention

Listener-visible events take the retention class N3 §4.3.1 assigns their event
type. This spec adds one constraint:

`control-action/requested`, `control-action/executed`, and
`control-action/approval-required` are `:audit` class (N3 §6). A deployment MAY
retain them longer than the one-year floor; it MUST NOT retain them for less.
A control action is the record of a human overriding the machine, and it is the
first thing an audit asks for.

---

## 6. OpenTelemetry Interoperability

### 6.1 OTel GenAI Alignment

Implementations SHOULD align with OpenTelemetry GenAI semantic conventions for:

- **Spans**: Agent execution, LLM calls, tool invocations
- **Metrics**: Token usage, latency, error rates
- **Events**: Status updates, milestones, gate results

### 6.2 Span Mapping

Miniforge events SHOULD map to OTel spans:

| Miniforge Event      | OTel Span Name           | OTel Attributes                    |
| -------------------- | ------------------------ | ---------------------------------- |
| `workflow/started`   | `miniforge.workflow`     | `workflow.id`, `workflow.version`  |
| `agent/started`      | `miniforge.agent`        | `agent.id`, `agent.role`           |
| `llm/request`        | `gen_ai.completion`      | `gen_ai.model`, `gen_ai.tokens`    |
| `tool/invoked`       | `miniforge.tool`         | `tool.name`, `tool.args`           |
| `gate/started`       | `miniforge.gate`         | `gate.id`, `gate.type`             |

### 6.3 OTLP Export

Implementations SHOULD support OTLP export:

```clojure
{:otel/enabled? boolean               ; Enable OTel export
 :otel/endpoint string                ; OTLP collector endpoint
 :otel/headers {...}                  ; Auth headers

 :otel/export-config
 {:spans? boolean                     ; Export spans
  :metrics? boolean                   ; Export metrics
  :logs? boolean                      ; Export log events
  :sampling-rate float}               ; Trace sampling rate

 :otel/resource-attributes
 {:service.name "miniforge"
  :service.version string
  :deployment.environment string}}
```

### 6.4 Trace Context Propagation

Implementations MUST support W3C Trace Context propagation:

- Incoming requests with `traceparent` header MUST continue the trace
- Outgoing requests (LLM calls, tool invocations) MUST propagate trace context
- Evidence bundles MUST include trace IDs for correlation

---

## 7. Cost and Volume Controls

### 7.1 Sampling Rules

Implementations MUST support event sampling:

```clojure
{:sampling/rules
 [{:event-types [keyword ...]         ; Events to sample
   :phases [keyword ...]              ; Phases to apply sampling
   :rate float                        ; Sampling rate 0.0-1.0
   :conditions {...}}]                ; Additional conditions

 :sampling/always-include
 [:workflow/started :workflow/completed :workflow/failed
  :gate/passed :gate/failed
  :control-action/*]}                 ; Never sample these
```

### 7.2 Aggregation Boundaries

High-frequency inner-loop events SHOULD be aggregated:

```clojure
{:aggregation/rules
 [{:event-types [:agent/status]
   :window-ms 5000                    ; Aggregation window
   :emit :last}                       ; :first | :last | :count | :summary

  {:event-types [:llm/token-delta]
   :window-ms 1000
   :emit :count}]}
```

### 7.3 Budget Controls

Listeners MUST respect budget controls:

```clojure
{:listener/budget
 {:max-events-per-second int          ; Rate limit
  :max-payload-bytes-per-second int   ; Bandwidth limit
  :max-history-query-rows int         ; Query result limit
  :max-concurrent-subscriptions int}} ; Subscription limit
```

---

## 8. Fleet and Enterprise Extensions

### 8.1 Fleet Aggregation

Fleet listeners aggregate events across workflows:

```clojure
{:fleet/aggregation
 {:metrics                            ; Aggregated metrics
  [:workflow-count :success-rate :mean-duration
   :token-usage :error-rate :gate-failure-rate]

  :windows                            ; Time windows
  [{:name :realtime :duration-ms 60000}
   {:name :hourly :duration-ms 3600000}
   {:name :daily :duration-ms 86400000}]

  :dimensions                         ; Aggregation dimensions
  [:workflow-type :phase :agent :gate]}}
```

### 8.2 Enterprise Multi-Tenancy

Enterprise listeners MUST support multi-tenant isolation:

```clojure
{:enterprise/tenant-isolation
 {:partition-by keyword               ; :org | :team | :project
  :cross-tenant-visibility boolean    ; Can see other tenants?
  :data-residency keyword}            ; :us | :eu | :apac

 :enterprise/compliance
 {:audit-log-destination string       ; External audit log
  :encryption-at-rest keyword         ; :aes-256-gcm
  :key-management keyword}}           ; :customer-managed | :platform-managed
```

### 8.3 Pattern Detection

Enterprise listeners MAY perform cross-org pattern detection:

```clojure
{:pattern-detection/rules
 [{:name string                       ; Pattern name
   :query {...}                       ; Pattern query
   :threshold {...}                   ; Trigger threshold
   :action keyword                    ; :alert | :annotate | :synthesize-policy
   :output keyword}]}                 ; :per-workflow | :aggregated | :policy-pack
```

Pattern detection outputs MAY feed into N7 (OPSV) for policy synthesis.

---

## 9. CLI/TUI Extensions (N5 Extension)

### 9.1 CLI Commands

Miniforge MUST add commands for listener management:

```bash
# Listener management
mf listener list                      # List active listeners
mf listener attach <workflow-id>      # Attach as OBSERVE listener
mf listener advise <workflow-id>      # Attach as ADVISE listener
mf listener control <workflow-id>     # Attach as CONTROL listener (requires auth)

# Control actions
mf workflow pause <workflow-id>       # Pause workflow
mf workflow resume <workflow-id>      # Resume workflow
mf workflow retry <workflow-id>       # Retry current phase
mf workflow rollback <workflow-id>    # Rollback to checkpoint
mf workflow cancel <workflow-id>      # Cancel workflow

mf agent quarantine <agent-id>        # Quarantine agent
mf agent budget <agent-id> --tokens=N # Adjust agent budget

mf gate approve <gate-id>             # Approve pending gate
mf gate override <gate-id> --reason=  # Override gate failure

mf fleet emergency-stop               # Emergency stop all workflows
mf fleet drain                        # Drain fleet
```

### 9.2 TUI Extensions

The TUI MUST provide:

- **Listener panel**: Show active listeners and their capabilities
- **Control palette**: Quick access to control actions (keyboard shortcuts)
- **Annotation overlay**: Display advisory annotations inline
- **Approval queue**: Pending multi-party approvals

---

## 10. Event Stream Extensions (N3 Extension)

### 10.1 Additional Event Types

OCI's listener and control-action event types are defined by **N3 §3.15**,
which owns every event wire contract (standard 020). They are listed here for
navigation only; their schemas are not reproduced.

| Event type | Defined in |
|------------|------------|
| `listener/attached` | N3 §3.15 |
| `listener/detached` | N3 §3.15 |
| `listener/overflow` | N3 §3.15 |
| `control-action/requested` | N3 §3.15 |
| `control-action/executed` | N3 §3.15 |
| `control-action/approval-required` | N3 §3.15 |
| `annotation/created` | N3 §3.15 |

Two properties of that family matter to implementers of this spec:

- These events take an **inherited scope** (N3 §2.3): `listener/*` takes the
  scope of the stream it annotates, and `control-action/*` and
  `annotation/created` take the scope of their target. Each carries
  `:scope/type` naming which scope it resolved to. An earlier revision of this
  section reproduced these schemas with a fixed `:workflow/id`, which made them
  unusable on the five non-workflow scopes N3 §5.3.1 now streams.
- `control-action/*` is `:audit` retention class (§5.3).

The remaining event types below are specific to this spec and are defined here.

#### checkpoint/reached

```clojure
{:event/type :checkpoint/reached
 :action/id uuid                       ; the originating checkpoint.request
 :workflow/id uuid
 :checkpoint/at keyword                ; :next-phase | :next-chain-edge | :pre-release
 :checkpoint/expires-at inst           ; OPTIONAL
 :message "Checkpoint reached: {at}"}
```

#### checkpoint/expired

```clojure
{:event/type :checkpoint/expired
 :action/id uuid
 :workflow/id uuid
 :checkpoint/resolution keyword        ; :auto-approved | :auto-rejected (per policy)
 :message "Checkpoint expired: {resolution}"}
```

#### model/overridden

```clojure
{:event/type :model/overridden
 :action/id uuid
 :workflow/id uuid
 :agent/id uuid                        ; OPTIONAL
 :model/provider keyword
 :model/id string
 :model/scope keyword                  ; :current-phase | :current-workflow | :current-call
 :model/expires-at inst                ; OPTIONAL
 :model/previous {:provider keyword :id string}
 :message "Model overridden: {provider}/{id}"}
```

#### model/override-expired

```clojure
{:event/type :model/override-expired
 :action/id uuid
 :workflow/id uuid
 :model/restored {:provider keyword :id string}
 :message "Model override expired; restored: {provider}/{id}"}
```

---

## 11. Evidence Extensions (N6 Extension)

### 11.1 Control Action Evidence

Control actions MUST be recorded in evidence bundles:

```clojure
{:evidence/control-actions
 [{:action/id uuid
   :action/type keyword
   :action/timestamp inst
   :action/requester {...}
   :action/justification string
   :action/approval {...}
   :action/result {...}
   :action/pre-state {...}            ; State before action
   :action/post-state {...}}]}        ; State after action
```

### 11.2 Annotation Evidence

Annotations MUST be recorded in evidence bundles:

```clojure
{:evidence/annotations
 [{:annotation/id uuid
   :annotation/type keyword
   :annotation/source {...}
   :annotation/target {...}
   :annotation/content {...}
   :annotation/timestamp inst}]}
```

---

## 12. Conformance Testing

### 12.1 Capability Tests

Conformance tests MUST verify:

1. OBSERVE listeners can subscribe but not emit annotations or actions
2. ADVISE listeners can emit annotations but not control actions
3. CONTROL listeners can request actions subject to RBAC
4. Unauthorized actions are rejected with appropriate errors

### 12.2 Control Action Tests

Conformance tests MUST verify:

1. All control actions emit appropriate events
2. High/Critical actions require approval when configured
3. Control actions are recorded in evidence bundles
4. Failed actions do not modify workflow state

### 12.3 Privacy Tests

Conformance tests MUST verify:

1. Redaction patterns are applied before emission
2. Privacy levels control content inclusion
3. Retention policies are enforced

### 12.4 Conformance Requirements

Requirement IDs are stable identifiers for the normative statements of this
spec. IDs are never reused; a withdrawn requirement is marked withdrawn, not
deleted.

#### Capability model

| ID | Level | Requirement |
|----|-------|-------------|
| N8.CAP.1 | MUST | Enforce the three capability levels of §2 — OBSERVE, ADVISE, CONTROL. |
| N8.CAP.2 | MUST NOT | Let an OBSERVE listener emit an annotation or request a control action (§2). |
| N8.CAP.3 | MUST NOT | Let an ADVISE listener request a control action (§2). |
| N8.CAP.4 | MUST | Resolve every listener to a principal and RBAC role (§2.3). |
| N8.CAP.5 | MUST | Reject a declared capability the principal's role does not permit, with HTTP 403 (§2.3, N3 §5.3.3). |

#### Control actions

| ID | Level | Requirement |
|----|-------|-------------|
| N8.CTL.1 | MUST | Emit `control-action/requested` and `control-action/executed` per N3 §3.15 for every action. |
| N8.CTL.2 | MUST | Require approval for High and Critical actions where configured (§3). |
| N8.CTL.3 | MUST | Record every control action in the evidence bundle (N6 §2.1, §11). |
| N8.CTL.4 | MUST NOT | Modify workflow state when an action fails (§12.2). |
| N8.CTL.5 | MUST | Carry `:scope/type` and the matching scope key on every control-action event (N3 §2.3). |
| N8.CTL.6 | MUST | Treat control-action events as `:audit` retention class (§5.3, N3 §4.3.1). |

#### Privacy

| ID | Level | Requirement |
|----|-------|-------------|
| N8.PRV.1 | MUST | Deliver fields by N3 §8.4's class and the principal's role (§5.1). |
| N8.PRV.2 | MUST | Suppress `:restricted` fields per-recipient at delivery, not per-event at emission (§5.1). |
| N8.PRV.3 | MUST NOT | Allow any configuration, listener type, or role to cause a N3 §8.1 excluded value to be emitted (§5.1). |
| N8.PRV.4 | MUST | Hold redaction patterns as EDN configuration with a schema (§5.2, dewey 007). |
| N8.PRV.5 | MUST NOT | Accept a function as a redaction configuration value (§5.2). |
| N8.PRV.6 | MUST | Apply deployment patterns in addition to N3 §8.1's excluded set, never instead of it (§5.2). |

### 12.5 Test Obligations

A conformance suite MUST cover, at minimum:

1. **Capability enforcement** — an OBSERVE listener's annotation attempt and a
   ADVISE listener's control-action attempt are both rejected
   (N8.CAP.2, N8.CAP.3).
2. **Non-workflow scope** — a listener attached to a pack or repository stream
   receives `listener/attached` carrying that scope's key, not `:workflow/id`
   (N8.CTL.5, N3 §2.3).
3. **Per-recipient suppression** — two listeners with different roles on one
   stream receive the same event with different `:restricted` fields present
   (N8.PRV.2).
4. **Redaction is not configurable away** — no combination of listener type,
   role, and deployment configuration causes a N3 §8.1 value to appear
   (N8.PRV.3).
5. **Config shape** — the redaction configuration round-trips through EDN and
   is rejected if it carries a function (N8.PRV.4, N8.PRV.5).
6. **Audit retention** — control-action events survive the one-year floor and
   are not expired by a shorter deployment policy (N8.CTL.6).

---

## 13. Minimal Compliant Implementation (MCI)

A minimal compliant OCI implementation MUST:

1. Support OBSERVE and ADVISE capability levels
2. Support at least: pause, resume, cancel control actions
3. Emit all required event types (§10.1)
4. Record control actions in evidence bundles
5. Deliver `:public` fields to every listener and honor `include-payloads=false` (§5.1)
6. Provide CLI commands for listener attachment

A minimal compliant implementation MAY defer:

- CONTROL capability with full RBAC
- Multi-party approval
- OTel export
- Enterprise multi-tenancy
- Pattern detection

---

## Annex A — Implementation Conformance Status (informative)

This annex is **informative**. It records where the miniforge implementation
diverges from the contract above, as of 2026-08-06. It is not a relaxation of
any requirement in §0–§14.

### A.1 Implemented

- **Listener capability and filtering.** `event-stream/listeners.clj`
  implements listener registration, capability checks, and `include-payloads`
  filtering (N8.CAP.1–N8.CAP.3, N8.PRV.1 partially).
- **Control action evidence.** `evidence-bundle/collector.clj` records
  control actions (N8.CTL.3).

### A.2 Specified, Not Implemented

- **Redaction (§5.2).** No redaction configuration exists anywhere in the
  tree — the only match for "redaction" is a test in `progress-detector`. N3
  §8's excluded-value set is therefore unenforced on this surface, as it is on
  the evidence surface (N6 Annex A). This is the same gap seen from three
  specs now, which is a signal about where implementation effort should go
  (N8.PRV.3–N8.PRV.6).
- **Per-recipient `:restricted` suppression (§5.1).** Filtering is
  per-subscription, not per-recipient by role (N8.PRV.2).
- **Inherited scope on listener events (§10.1).** Listener events carry
  `:workflow/id`; `:scope/type` is not emitted, so the five non-workflow
  scopes of N3 §2.3 cannot be observed (N8.CTL.5).

### A.3 Structural

- **Audit retention (§5.3)** is unenforced — there is no retention floor for
  control-action events (N8.CTL.6).

---

## 14. References

- **N1**: Core Architecture & Concepts — Listener as Agent specialization
- **N3**: Event Stream & Observability — Base event contract
- **N4**: Policy Packs & Gates — RBAC and gate governance
- **N5**: CLI/TUI/API — Command surface
- **N6**: Evidence & Provenance — Audit requirements
- **N7**: Operational Policy Synthesis — Pattern detection integration
- **OpenTelemetry GenAI**: Semantic conventions for AI observability
- **RFC 2119**: Requirement level keywords

---

**Version History:**

- 0.4.0-draft (2026-08-06): Spec-completion pass. §5 carried a parallel model
  for concerns N3 owns — privacy levels `metadata-only | redacted | full`, a
  `:redaction/patterns` regex table, a `:redaction/field-rules` vocabulary, and
  a `:retention/policies` schema with its own day counts. All withdrawn: N3 §8
  owns redaction and N3 §4.3 owns retention, and four overlapping vocabularies
  meant an operator configuring redaction here could not tell whether N3 §8.1's
  MUST NOT still applied. §5 now defines only what is listener-specific — which
  principal sees which field class. `:redaction/custom-fn function` withdrawn
  as a config-as-data violation (dewey 007): a function in a config map cannot
  be serialized, diffed, or audited. §10.1 reproduced N3 §3.15's event schemas
  with a fixed `:workflow/id`, which made them unusable on the five
  non-workflow scopes N3 §5.3.1 streams; now a reference table. §12.4–§12.5
  conformance requirement IDs and test obligations. Annex A records
  implementation divergence.

- 0.3.0-draft (2026-04-23): Control-action surface extensions — Checkpoint Control
  (§3.1.5: request/approve/reject) for governed pause-points in Workflow Packs and
  chained workflows (N2 §14); Model Control (§3.1.6: override/set/clear) for
  policy-bounded LLM model substitution with required justification, expiration,
  and no silent fallback. Corresponding events `checkpoint/reached`,
  `checkpoint/expired`, `model/overridden`, `model/override-expired` added in §10
- 0.2.0-draft (2026-03-08): Safe-mode posture (§3.4) — triggers, behavior, exit protocol,
  state/events; unified autonomy model back-reference (N1 §5.6)
- 0.1.0-draft (2026-02-01): Initial observability control interface specification
