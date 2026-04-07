<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N8 — Observability Control Interface

**Version:** 0.2.0-draft
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

### 5.1 Privacy Levels

Event payloads MUST support configurable privacy levels:

| Level          | Content Included                              |
| -------------- | --------------------------------------------- |
| `metadata-only`| Event envelope only, no prompt/response content|
| `redacted`     | Content with sensitive patterns removed       |
| `full`         | Complete content (encrypted at rest)          |

### 5.2 Redaction Requirements

Implementations MUST provide redaction hooks at emission time:

```clojure
{:redaction/patterns
 [{:pattern regex                     ; Pattern to match
   :replacement string                ; Replacement text
   :applies-to [keyword ...]}]        ; Event fields to scan

 :redaction/field-rules
 {:prompt-content keyword             ; :include | :redact | :exclude
  :response-content keyword
  :tool-args keyword
  :tool-results keyword}

 :redaction/custom-fn function}       ; Custom redaction function
```

### 5.3 Retention Policies

Implementations MUST support configurable retention:

```clojure
{:retention/policies
 [{:event-types [keyword ...]         ; Which events this applies to
   :privacy-level keyword             ; Which privacy level
   :retention-days int                ; Days to retain
   :archive-after-days int            ; Days before archiving
   :delete-after-days int}]           ; Days before deletion

 :retention/compliance
 {:audit-events-min-days int          ; Minimum for audit events
  :control-actions-min-days int       ; Minimum for control actions
  :evidence-bundles-min-days int}}    ; Minimum for evidence
```

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

OCI adds these event types to N3:

#### listener/attached

```clojure
{:event/type :listener/attached
 :listener/id uuid
 :listener/type keyword
 :listener/capability keyword
 :workflow/id uuid
 :message "Listener attached: {type} with {capability} capability"}
```

#### listener/detached

```clojure
{:event/type :listener/detached
 :listener/id uuid
 :workflow/id uuid
 :listener/reason keyword             ; :disconnect | :timeout | :revoked
 :message "Listener detached: {reason}"}
```

#### control-action/requested

```clojure
{:event/type :control-action/requested
 :action/id uuid
 :action/type keyword
 :action/target {...}
 :action/requester {...}
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

---

## 13. Minimal Compliant Implementation (MCI)

A minimal compliant OCI implementation MUST:

1. Support OBSERVE and ADVISE capability levels
2. Support at least: pause, resume, cancel control actions
3. Emit all required event types (§10.1)
4. Record control actions in evidence bundles
5. Support metadata-only privacy level
6. Provide CLI commands for listener attachment

A minimal compliant implementation MAY defer:

- CONTROL capability with full RBAC
- Multi-party approval
- OTel export
- Enterprise multi-tenancy
- Pattern detection

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

- 0.2.0-draft (2026-03-08): Safe-mode posture (§3.4) — triggers, behavior, exit protocol,
  state/events; unified autonomy model back-reference (N1 §5.6)
- 0.1.0-draft (2026-02-01): Initial observability control interface specification
