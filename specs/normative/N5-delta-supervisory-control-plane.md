<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N5 Delta — Supervisory Control Plane

- **Spec ID:** `N5-delta-supervisory-control-plane-v1`
- **Version:** `0.1.0-draft`
- **Status:** Draft
- **Date:** 2026-04-02
- **Amends:** N5 — Interface Standard: CLI/TUI/API
- **Related:** N3 (event stream), N4 (policy packs), N6 (evidence), N8 (observability control), N9 (external PR
  integration), pr-monitor-loop-v1 (autonomous PR comment resolution)

## 1. Purpose

This delta amends N5 to formalize the TUI as a **supervisory control plane** for agent-executed workflows, rather than a
human-command surface.

N5 §5 already establishes the right posture — the console is a monitoring interface and evidence viewer, not a PR
management tool or chat interface. This delta sharpens that into normative requirements for the supervisory domain
model, governance states, and bounded interventions that the TUI must support.

### 1.1 What this delta adds to N5

- Canonical supervisory entities the TUI renders (§3)
- PR governance states derived from policy evaluation (§4)
- Attention items as a first-class supervisory concept (§5)
- Waiver semantics for policy overrides (§6)
- Bounded intervention vocabulary (§7)
- Monitor mode as default TUI posture (§8)
- Durable startup and stale-read requirements (§9)

### 1.2 What this delta does NOT change

- N5 §2 CLI command taxonomy — unchanged
- N5 §4 API surface — unchanged
- N5 §6 manual override mechanisms — extended, not replaced
- N5 §3.2 existing TUI views — retained, monitor mode added

## 2. Operator model

### 2.1 Primary operator

N5 §5.3 establishes that the user monitors an autonomous factory. This delta makes explicit:

The primary operator of miniforge workflows MUST be the agent session (Claude Code, Codex, managed orchestration loops,
or equivalent). The agent drives workflow creation, execution, and PR operations through MCP or equivalent tool
interfaces.

### 2.2 Human role

The human's role in the supervisory model is:

- **Policy author** — defines governance rules that agents execute against
- **Supervisor** — monitors agent activity and system health
- **Exception handler** — intervenes when automation encounters conditions outside its authority
- **Auditor** — reviews evidence bundles and governance dispositions

The TUI MUST be optimized for these roles, not for direct workflow operation.

## 3. Supervisory entities — v1

The TUI MUST render from canonical supervisory entities. These entities MUST exist as explicit data shapes with minimum
required keys. Implementations SHOULD validate at boundaries using open schemas (e.g., Malli `:map` with required keys;
additional keys pass through without validation failure).

### 3.1 v1 entities (MUST)

The following entities are required for the supervisory TUI v1:

#### WorkflowRun

A concrete execution instance of a workflow, distinct from the workflow definition/spec.

Minimum required keys:

```clojure
{:workflow-run/id          uuid?       ;; stable run identity
 :workflow-run/workflow-key string?    ;; reference to workflow spec
 :workflow-run/intent       string?    ;; human-readable intent description
 :workflow-run/status       keyword?   ;; see §3.2
 :workflow-run/current-phase keyword?  ;; current phase keyword
 :workflow-run/started-at   inst?      ;; run start timestamp
 :workflow-run/updated-at   inst?      ;; last state change timestamp
 :workflow-run/trigger-source keyword? ;; :mcp, :cli, :api, :chain
 :workflow-run/correlation-id uuid?}   ;; links to related entities
```

#### PolicyEvaluation

An immutable record of a completed policy evaluation against a PR or artifact.

Minimum required keys:

```clojure
{:policy-eval/id            uuid?
 :policy-eval/target-type   keyword?   ;; :pr, :artifact, :workflow-output
 :policy-eval/target-id     any?       ;; [repo number] for PRs, uuid for others
 :policy-eval/passed?       boolean?
 :policy-eval/packs-applied vector?    ;; pack identifiers evaluated
 :policy-eval/violations    vector?    ;; vector of PolicyViolation maps
 :policy-eval/evaluated-at  inst?}
```

Completed PolicyEvaluations MUST be immutable. A re-evaluation MUST produce a new record, not mutate the previous one.

#### PolicyViolation

A single rule failure within a PolicyEvaluation.

Minimum required keys:

```clojure
{:violation/rule-id     keyword?
 :violation/severity    keyword?   ;; :critical, :high, :medium, :low, :info
 :violation/category    keyword?   ;; grouping key for aggregate display
 :violation/message     string?
 :violation/location    string?    ;; optional: file:line or entity reference
 :violation/remediable? boolean?}  ;; whether auto-fix is available
```

#### AttentionItem

A derived supervisory signal indicating something needs human awareness or action.

Minimum required keys:

```clojure
{:attention/id          uuid?
 :attention/severity    keyword?   ;; :critical, :warning, :info
 :attention/source-type keyword?   ;; :workflow, :pr, :train, :policy, :agent
 :attention/source-id   any?       ;; ID of the entity that triggered attention
 :attention/summary     string?    ;; one-line description
 :attention/derived-at  inst?      ;; when the attention was computed
 :attention/resolved?   boolean?}  ;; whether the underlying condition resolved
```

AttentionItems MUST be derivable from canonical supervisory state. They MAY be materialized/cached for performance but
MUST NOT be the source of truth — the underlying entity state is authoritative.

#### Waiver

A durable record that a policy failure has been acknowledged and overridden with justification.

Minimum required keys:

```clojure
{:waiver/id              uuid?
 :waiver/evaluation-id   uuid?      ;; the PolicyEvaluation being waived
 :waiver/violations      vector?    ;; rule-ids being waived (subset or all)
 :waiver/actor           string?    ;; who granted the waiver
 :waiver/reason          string?    ;; justification
 :waiver/timestamp       inst?}
```

A Waiver MUST NOT mutate or overwrite the original PolicyEvaluation. A waived entity MUST remain visibly waived (not
"passing") in all supervisory projections.

#### EvidenceBundle

Reference to the complete audit trail for a workflow run. Already defined in N6; this delta adds the supervisory
projection requirement:

Minimum required keys for supervisory display:

```clojure
{:evidence/id            uuid?
 :evidence/workflow-run-id uuid?
 :evidence/intent        string?
 :evidence/outcome       keyword?   ;; :pass, :fail, :partial
 :evidence/phase-count   int?
 :evidence/gate-summary  map?}      ;; {:passed N :failed N :skipped N}
```

### 3.2 Workflow execution states

WorkflowRun `:workflow-run/status` MUST distinguish at least:

| State | Meaning |
|-------|---------|
| `:queued` | Accepted but not yet started |
| `:running` | Actively executing a phase |
| `:paused` | Suspended by human intervention |
| `:blocked` | Cannot proceed (dependency, gate failure) |
| `:completed` | All phases finished successfully |
| `:failed` | Terminal failure |
| `:cancelled` | Cancelled by human or agent |

Terminal states (`:completed`, `:failed`, `:cancelled`) MUST NOT transition back to active states. A retry MUST produce
a new WorkflowRun.

### 3.3 Existing entities carried forward

The following entities already exist in sufficient form across N1, N4, N9, and existing components. They do not require
new formalization for v1 but MUST be correlatable to v1 entities:

- **AgentSession** — implemented as agent records in the `control-plane/registry` component (PR #326). The registry
  provides: vendor, external-id, name, capabilities, status, heartbeat timestamps, metadata. The `adapter-claude-code`
  component discovers Claude Code sessions via `~/.claude/projects/`. The orchestrator coordinates discovery, polling,
  heartbeat monitoring, and decision delivery. States: `:idle`, `:starting`, `:executing`, `:blocked`, `:completed`,
  `:failed`. Events: `:control-plane/agent-discovered`, `:control-plane/status-changed`,
  `:control-plane/decision-submitted`, `:control-plane/decision-resolved`.
- **PullRequest** — well-modeled in N9 and the pr-sync component
- **PRTrain** — defined in N9 and the pr-train component
- **WorkflowPhase** — defined in N2; tracked in events

## 4. PR governance states

PR supervisory state MUST be derived from PolicyEvaluation + Waiver records. It MUST NOT be freely editable by the UI.

Required governance states:

| State | Derivation |
|-------|------------|
| `:not-evaluated` | No PolicyEvaluation exists for this PR |
| `:policy-passing` | Most recent evaluation has `passed? = true` |
| `:policy-failing` | Most recent evaluation has `passed? = false`, no waiver |
| `:waived` | Evaluation failed but a Waiver record exists covering the violations |
| `:escalated` | Evaluation failed, no waiver, marked for human review |

The TUI governance surface MUST display these states as visually distinct.

## 5. Attention derivation

The TUI MUST derive attention items from supervisory state. Attention items are computed, not manually created.

### 5.1 Required attention rules (v1)

| Condition | Severity | Summary pattern |
|-----------|----------|----------------|
| Workflow failed with no retry | `:critical` | "Workflow {name} failed: {error}" |
| PR blocked on policy failure, no remediation | `:critical` | "PR {repo}#{number} failing policy, no auto-fix" |
| PR train blocked | `:critical` | "Train blocked at {repo}#{number}: {reason}" |
| PR monitor budget exhausted | `:critical` | "PR #{number} monitor exhausted — human action needed" |
| PR monitor escalated | `:critical` | "PR #{number} escalated: {reason}" |
| Workflow stale (no progress > threshold) | `:warning` | "Workflow {name} stale for {duration}" |
| PR behind main with merge conflicts | `:warning` | "PR {repo}#{number} has merge conflicts" |
| Policy violation on PR in active train | `:warning` | "Train PR {repo}#{number} failing policy" |
| PR monitor budget warning | `:warning` | "PR #{number} monitor approaching budget limit" |
| Workflow completed successfully | `:info` | "Workflow {name} completed" |
| PR merged | `:info` | "PR {repo}#{number} merged" |
| PR monitor fix pushed | `:info` | "PR #{number} fix pushed: {commit-sha}" |

Implementations MAY add additional attention rules.

Note: The PR monitor loop (`:pr-monitor/*` events from the pr-lifecycle component) is a primary source of supervisory
events. The monitor autonomously resolves review comments on miniforge-authored PRs, emitting fine-grained events that
the TUI MUST subscribe to for real-time visibility into the autonomous feedback loop.

### 5.2 Attention lifecycle

- Attention items MUST auto-resolve when the underlying condition resolves
- `:info` items SHOULD auto-dismiss after a configurable period
- `:critical` and `:warning` items MUST remain visible until resolved or acknowledged

## 6. Waiver semantics

Waivers extend N5 §6 (Manual Override Mechanisms) with durable governance semantics.

### 6.1 Relationship to N5 §6 overrides

N5 §6.1.2 defines gate failure overrides with justification. This delta formalizes those overrides as Waiver records
that:

- Are stored durably (not just logged in the evidence bundle)
- Are queryable for governance reporting
- Affect derived PR governance state (§4)
- Are visible in the TUI governance surface

### 6.2 Waiver constraints

- A Waiver MUST reference a specific PolicyEvaluation
- A Waiver MUST list which violations it covers
- A Waiver MUST NOT retroactively change the evaluation result — the evaluation remains "failed," the PR governance
  state becomes `:waived`
- The TUI MUST display waived PRs distinctly from passing PRs

## 7. Bounded intervention vocabulary

The TUI MUST support a bounded set of supervisory interventions. These extend N5 §6 with explicit action types.

### 7.1 v1 interventions (MUST)

| Intervention | Target | Effect |
|-------------|--------|--------|
| `acknowledge` | AttentionItem | Marks item as seen; does not resolve |
| `retry` | WorkflowRun (failed) | Creates a new run from the same spec |
| `pause` | WorkflowRun (running) | Suspends execution |
| `resume` | WorkflowRun (paused) | Resumes execution |
| `cancel` | WorkflowRun (active) | Terminates execution |
| `waive` | PolicyEvaluation | Creates a Waiver record with reason |
| `re-evaluate` | PullRequest | Triggers a new PolicyEvaluation |

### 7.2 Intervention recording

Interventions MUST produce a durable record per N5 §6.2. The existing override logging schema in N5 §6.2 is sufficient;
this delta does not add new fields.

### 7.3 Boundary constraint

The supervisory surface MUST NOT expand beyond the intervention vocabulary defined here without a spec amendment. It
MUST NOT become a general-purpose command shell.

## 8. Monitor mode

### 8.1 Default posture

The TUI SHOULD default to a monitor mode optimized for passive supervision.

Monitor mode MUST display, on a single screen:

- Active and recent workflow runs with live status
- PR train or fleet summary, including PR monitor loop status per PR (monitoring/idle/exhausted)
- Attention items requiring action

Monitor mode SHOULD also display:

- Aggregate policy health (pass rate, top violation categories)
- PR monitor loop activity summary (PRs being monitored, comments pending, fixes pushed)

### 8.2 Responsive rendering

Monitor mode MUST render usefully at 60 columns (tmux side-pane scenario). Implementations SHOULD adapt layout to
available width:

- 60–80 columns: stacked zones, abbreviated columns
- 80–120 columns: split layout
- 120+ columns: full-width tables

### 8.3 Drill-down

From monitor mode, the user MUST be able to drill into existing detail views (N5 §3.2.2 Workflow Detail, §3.2.9 PR
Detail) and return via Esc.

### 8.4 Relationship to existing views

Monitor mode is additive. Existing views defined in N5 §3.2 MUST remain accessible. Monitor mode becomes the default
entry point; Tab or number keys navigate to other views.

## 9. Durable startup and stale-read

### 9.1 Startup state

On launch, the TUI MUST show useful supervisory state from persisted data, even when no active workflow is running.

### 9.2 Stale-read degradation

If live event subscription is unavailable, the TUI MUST degrade to stale read-only supervisory state rather than
appearing empty. The TUI SHOULD display a visible stale-data indicator.

### 9.3 Catch-up

When live subscription becomes available after displaying stale state, the TUI MUST catch up to current state without
requiring a restart.

## 10. Control plane integration

### 10.1 Existing infrastructure

The `control-plane` component (PR #326) already implements multi-vendor agent session management:

- **Agent registry** — CRUD, heartbeat tracking, state transitions for agents across vendors
- **Adapter protocol** — vendor-specific adapters (Claude Code adapter discovers sessions via `~/.claude/projects/`)
- **Decision queue** — agents submit decisions, humans resolve them, results delivered back
- **Heartbeat watchdog** — background liveness monitoring, stale agent detection
- **Orchestrator** — coordinates discovery, polling, decision delivery across adapters
- **Events** — `:control-plane/agent-discovered`, `:control-plane/status-changed`, `:control-plane/decision-submitted`,
  `:control-plane/decision-resolved`

Agent records in the registry carry: vendor, external-id, name, capabilities, status, heartbeat timestamps, metadata.
Agent states: `:idle`, `:starting`, `:executing`, `:blocked`, `:completed`, `:failed`.

### 10.2 TUI integration requirement

The TUI monitor MUST display control plane state:

- Active agent sessions with vendor, name, status, last heartbeat
- Pending decisions requiring human resolution
- Agent state transitions as they occur

Control plane events (`:control-plane/*`) MUST be handled by the TUI event subscription alongside `:workflow/*` and
`:pr-monitor/*` events.

The `control-plane-completion.spec.edn` work spec covers wiring decision delivery and event emission from the
orchestrator. The TUI work (this delta + companion work spec) covers rendering that data in monitor mode.

### 10.3 Agent sessions in the monitor

The monitor's workflow ticker zone SHOULD include agent session rows when the control plane is active, showing which
agents are operating and their current state. When an agent is `:blocked` on a decision, the attention bar MUST surface
it as a `:critical` item — the human needs to act.

## 11. Deferred to Fleet

The following concepts are recognized as architecturally important but deferred to Fleet-scale specifications. See
`work/fleet-supervisory-deferred.spec.edn` for tracking.

| Concept | Reason for deferral |
|---------|-------------------|
| `Lens` abstraction | Extract after monitor patterns stabilize |
| Full `Intervention` audit trail | Simple recording (N5 §6.2) sufficient for v1 |
| Event versioning | Existing event envelope sufficient for v1 |
| Cross-entity correlation IDs | Add incrementally where missing |
| Multi-project fleet aggregation | Single-project dogfooding is current scope |

## 12. Conformance

### 12.1 Supervisory entity validation

Implementations MUST validate supervisory entities at component boundaries using open schemas. Unknown keys MUST pass
through without error. Required keys MUST be present and correctly typed.

### 12.2 Governance state invariants

1. Every PR governance state displayed in the TUI MUST be derivable from PolicyEvaluation and Waiver records
2. Completed PolicyEvaluations MUST be immutable
3. Waivers MUST be separate records, not mutations of evaluations
4. AttentionItems MUST be derivable from canonical state
5. Terminal WorkflowRun states MUST NOT transition to active states

### 12.3 Monitor mode conformance

1. Monitor mode MUST render at 60 columns without data loss (truncation is acceptable, absence is not)
2. Monitor mode MUST update without user interaction when events arrive
3. Monitor mode MUST show attention items without requiring navigation
