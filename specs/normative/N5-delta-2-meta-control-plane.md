# N5 Delta 2 — Meta Control Plane: Embedded Agent CLI & Nuclear-Plant Supervisory UX

- **Spec ID:** `N5-delta-2-meta-control-plane-v1`
- **Version:** `0.1.0-draft`
- **Status:** Draft
- **Date:** 2026-04-10
- **Amends:** N5 — Interface Standard: CLI/TUI/API; N5-delta-1 (supervisory control plane)
- **Related:** N8 (OCI), N11 (native control console), control-plane-completion, tui-supervisory-control-plane-v1

## 1. Purpose

This delta amends N5 and N5-delta-1 to formalize the TUI (and native console) as a **meta-meta control plane** — not
just a supervisory monitor of agent workflows, but the primary command center from which the human operator drives,
observes, and intervenes in the entire Miniforge system without leaving a single surface.

N5-delta-1 established the supervisory posture: human monitors agent-operated system. This delta sharpens that into
the nuclear-plant control room model: **the operator never leaves the room.** Everything streams in, attention auto-
surfaces, interventions happen in-place, and the operator can issue agent commands directly from the control surface.

### 1.1 What this delta adds

- Native-first architecture: Rust core + platform UX shells (§2A)
- Embedded agent CLI as a first-class zone (§3)
- Meta-meta loop formalization: observe your observation (§4)
- Interaction tier separation: native for CONTROL, web for OBSERVE (§5)
- Agent session as narrative: live tool calls, decisions, budget burn (§6)
- Native-first latency requirements (§7)
- Backend delegation: embedded agent uses configured Miniforge backend (§8)
- TUI as fallback/SSH surface, not primary (§9.3)

### 1.2 What this delta does NOT change

- N5-delta-1 supervisory entities — retained, extended
- N5-delta-1 bounded intervention vocabulary — retained, extended
- N5-delta-1 governance states — unchanged
- N8 OCI capability levels — unchanged, but interaction tier assignment formalized

### 1.3 Motivation from dogfooding

Running Miniforge in anger produces a characteristic workflow: the operator sits in Claude Code or Codex, agents drive
workflows through MCP, PRs flow through review and merge, the PR monitor loop resolves feedback autonomously. The
operator needs to see all of this — workflow progress, PR trains, policy governance, agent decisions, budget burn,
monitor loop activity — in a single surface, and needs to act on exceptions without context-switching to another tool.

The prior spec's deprioritization of chat integration ("Agent session IS the conversation") was correct for a world
where the TUI is a side-pane monitor. But the meta-meta loop inverts this: the TUI _is_ the primary surface, and the
agent sessions (Claude Code, Codex, native miniforge agents) are rendered _within_ it. The operator doesn't leave
the TUI to talk to an agent — they talk to the system from the TUI, and the system delegates to whatever backend is
configured.

## 2. Operator model (amended)

### 2.1 Meta-meta loop

N5-delta-1 §2 established the human as supervisor of agent-operated workflows. This delta adds a recursive layer:

The human operates a **meta-meta loop**:

- **Loop 0 (inner):** Agent executes workflow phases (plan → implement → test → review → release)
- **Loop 1 (outer):** PR monitor resolves review feedback, pushes fixes, manages budget
- **Loop 2 (meta):** Human supervises Loop 0 and Loop 1 via the TUI monitor — sees progress, exceptions, governance
- **Loop 3 (meta-meta):** Human issues commands to the system _from within the TUI_, spawning new agent sessions,
  adjusting policy, resolving decisions — observing the effect of their interventions in real time

The TUI MUST support all four loops simultaneously. Loop 2 is passive observation (N5-delta-1). Loop 3 is active
command (this delta).

### 2.2 Human roles (extended)

N5-delta-1 §2.2 defined four roles. This delta adds:

- **Commander** — issues high-level intent to the system ("review this PR", "run the auth workflow", "what's blocking
  the train?") via the embedded agent CLI, with the system delegating to the appropriate backend and workflow

The commander role does not bypass the supervisory model. Commands issued via the embedded agent CLI produce the same
WorkflowRuns, PolicyEvaluations, and evidence bundles as any other trigger source. The TUI renders them identically.

## 2A. Native-first architecture

### 2A.1 Principle

The control console is a **native application**, not a terminal program. The TUI is retained as a fallback for SSH and
headless scenarios, but the primary CONTROL-tier experience is a native app with proper rendering, resizable panes,
syntax highlighting, and platform input handling.

The meta-meta loop requires information density that terminals cannot deliver: code diffs with syntax highlighting next
to agent reasoning next to PR governance state next to budget telemetry. Box-drawing characters are the ceiling for
TUI visual density. The nuclear-plant control room has _screens_, not terminals.

### 2A.2 Rust core + renderers

The architecture follows the Thesium pattern: **Rust owns everything except platform-native rendering**.

```
┌─────────────────────────────────────────────────────────────────┐
│                           Renderers                             │
│                                                                 │
│  ┌──────────────────┐    ┌──────────────┐  ┌────────────────┐  │
│  │ Rust TUI          │    │ macOS (Swift) │  │ Linux/Windows  │  │
│  │ (ratatui +        │    │  SwiftUI +    │  │ (future)       │  │
│  │  crossterm)       │    │  AppKit       │  │                │  │
│  │                   │    └──────┬───────┘  └───────┬────────┘  │
│  │ In-process: uses  │           │                   │          │
│  │ Rust core crates  │           │ C-ABI (JSON cmd/  │          │
│  │ directly, no FFI  │           │ response + event  │          │
│  └────────┬──────────┘           │ callbacks)        │          │
│           │                      │                   │          │
│           │  ┌───────────────────┴───────────────────┘          │
│           │  │                                                  │
│  ┌────────┴──┴─────────────────────────────────────────────────┐│
│  │                    Rust Core (libminiforge_control)          ││
│  │                                                              ││
│  │  ┌──────────────┐ ┌───────────────┐ ┌────────────────────┐ ││
│  │  │ Event Stream  │ │ Supervisory   │ │ Control Plane      │ ││
│  │  │ Client        │ │ State Manager │ │ Protocol Client    │ ││
│  │  │ (subscribe,   │ │ (entities,    │ │ (agent registry,   │ ││
│  │  │  replay,      │ │  projections, │ │  decision queue,   │ ││
│  │  │  catch-up)    │ │  attention    │ │  heartbeat,        │ ││
│  │  │               │ │  derivation)  │ │  commands)         │ ││
│  │  └──────────────┘ └───────────────┘ └────────────────────┘ ││
│  │  ┌──────────────┐ ┌───────────────┐ ┌────────────────────┐ ││
│  │  │ CLI Command   │ │ Backend       │ │ Persistence        │ ││
│  │  │ Dispatch      │ │ Delegation    │ │ (startup load,     │ ││
│  │  │ (parse,       │ │ (model        │ │  stale-read,       │ ││
│  │  │  route,       │ │  catalog,     │ │  cache)            │ ││
│  │  │  execute)     │ │  role config) │ │                    │ ││
│  │  └──────────────┘ └───────────────┘ └────────────────────┘ ││
│  └──────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘

Rendering hierarchy:
  Rust TUI:    Rust core crates → ratatui (in-process, no FFI, single binary)
  macOS app:   Rust core → C-ABI facade → Swift/SwiftUI (.app bundle)
  Future:      Rust core → C-ABI facade → GTK/WinUI
```

The Rust TUI and the native app share the same core crates — contracts, state manager, projections, CLI dispatch,
event client, control plane client. The only difference is the rendering layer. The Rust TUI uses the crates
directly (in-process, zero overhead); the native app uses the C-ABI facade (JSON serialization at the boundary).

This eliminates the Clojure TUI entirely. There is no dual-model divergence risk because there is only one model
implementation (Rust). The Clojure components continue to own the server side (event stream, control plane, workflows,
policy evaluation); the Rust core is the client that renders and commands.

### 2A.3 C-ABI facade contract

The Rust core exposes a C-ABI facade following the Thesium pattern, extended for streaming:

```c
// Lifecycle
MiniforgeControlHandle* miniforge_control_init(const char* config_json);
void miniforge_control_shutdown(MiniforgeControlHandle* handle);

// Command dispatch (request/response)
char* miniforge_control_dispatch(
    MiniforgeControlHandle* handle,
    const char* command_json
);  // Returns JSON response. Caller frees with miniforge_control_string_free.

// Streaming event subscription (the Thesium progress callback, generalized)
typedef void (*MiniforgeEventCallback)(
    void* user_data,
    const char* event_json    // supervisory event: state change, attention, agent status
);

int miniforge_control_subscribe(
    MiniforgeControlHandle* handle,
    MiniforgeEventCallback callback,
    void* user_data
);  // Returns subscription ID. Callback fires on background thread.

void miniforge_control_unsubscribe(
    MiniforgeControlHandle* handle,
    int subscription_id
);

void miniforge_control_string_free(char* ptr);
```

The `dispatch` function handles all commands (structured and natural language). The `subscribe` function delivers
real-time supervisory events to the UX shell — this is the continuous version of Thesium's per-operation progress
callback.

### 2A.4 State ownership

The Rust core owns supervisory state. The UX shell renders it. State flows one direction:

```
Miniforge event stream → Rust core (state management) → UX shell (rendering)
                                ↑
Operator input → UX shell → Rust core (command dispatch) → Miniforge system
```

The UX shell MUST NOT maintain its own copy of supervisory state. It receives state snapshots and deltas from the
Rust core via the subscription callback and renders them. This prevents state divergence between platforms.

### 2A.5 Rust core relationship to Clojure components

The Rust core is a **client** of the Miniforge system, not a replacement for Clojure components. It:

- Subscribes to the Clojure event stream (via file watching, SSE, or IPC)
- Sends commands to the Clojure control plane (via MCP, CLI, or IPC)
- Maintains a local projection of supervisory state for low-latency rendering
- Does NOT run workflows, evaluate policies, or manage agents — those remain in Clojure

The Rust core replaces the Clojure TUI entirely. The Clojure `tui-engine` and `tui-views` components are superseded
by the Rust state manager and renderers. This is a clean boundary: Clojure owns the server (event stream, control
plane, workflows); Rust owns the client (state projection, rendering, operator interaction).

### 2A.6 Thesium convergence

Both Thesium (risk analysis) and the Miniforge control console follow the same Rust-core + platform-shell pattern.
Over time, the facade patterns, build infrastructure, and platform shells MAY converge into a shared application
framework. This delta does not require convergence but acknowledges the architectural alignment.

## 3. Embedded agent CLI

### 3.1 Concept

The TUI MUST include an embedded agent CLI zone — a command input area where the human can issue natural-language
instructions or structured commands to the Miniforge system. This is NOT a chat window. It is a command surface that
produces observable, auditable system actions.

The embedded agent CLI replaces the deprioritized "Chat integration" from the prior work spec. The key difference:
chat implies conversation; the agent CLI implies command-and-observe. The human issues an intent, the system acts,
and the result appears in the supervisory zones (workflow ticker, PR fleet, attention bar).

### 3.2 Input model

The embedded agent CLI MUST support:

| Input type              | Example                           | Effect                                                  |
| ----------------------- | --------------------------------- | ------------------------------------------------------- |
| Natural language intent | "review PR #247"                  | System delegates to configured backend, starts workflow |
| Structured command      | `:workflow execute auth-flow.edn` | Direct workflow execution                               |
| Query                   | "what's blocking the train?"      | System analyzes supervisory state and responds          |
| Decision resolution     | (from attention item context)     | Resolves pending agent decision                         |

### 3.3 Backend delegation

The embedded agent CLI MUST delegate to whatever LLM backend is configured for the relevant agent role (§8). It MUST
NOT hardcode a specific LLM provider. The agent CLI is a _frontend_ to the Miniforge agent system, not a standalone
LLM chat.

### 3.4 Session identity

Commands issued via the embedded agent CLI MUST produce agent sessions visible in the control plane registry with:

```clojure
{:agent/vendor       :miniforge-tui  ;; distinguishes from :claude-code, :codex, etc.
 :agent/trigger      :embedded-cli   ;; trigger source
 :agent/name         string?         ;; human-readable, e.g., "tui-review-pr-247"
 :agent/capabilities vector?         ;; inherited from role config
 :agent/budget       map?}           ;; from role config or operator override
```

These sessions appear in the agent sessions zone alongside externally-triggered sessions, identifiable by vendor.

### 3.5 Output model

Agent CLI output MUST NOT monopolize the screen. Results flow into the standard supervisory zones:

- Workflow started → appears in workflow ticker
- PR created/updated → appears in PR fleet zone
- Query answered → appears in a transient response area (bottom panel, overlay, or inline)
- Error/failure → surfaces as attention item

Long-running operations MUST show progress in the workflow ticker, not block the CLI input.

### 3.6 Zone placement

The embedded agent CLI SHOULD be positioned as a persistent input bar (similar to vim's `:` command line or k9s filter
bar). It MUST be dismissable. It MUST NOT obscure the monitor zones when inactive.

Activation: `/` or `:` key (consistent with existing command mode keybinding).

## 4. Agent session as narrative

### 4.1 Live session rendering

N5-delta-1 §10.3 required agent sessions in the monitor with vendor, name, status, and heartbeat. This delta extends
that to a **narrative view** — the agent session rendered as a live activity stream, the way Claude Code renders its
own tool use to the user.

When an agent session is selected (Enter on an agent row in the monitor), the TUI MUST show:

- Current activity: what the agent is doing right now (tool call, thinking, waiting)
- Recent actions: last N tool calls with timestamps (file reads, greps, edits, commands)
- Decision state: if blocked, show the decision summary and resolution options
- Budget consumption: tokens used / limit, cost used / limit, calls made
- Subagent activity: if the agent has spawned subagents, show them nested

This is the "nuclear plant instrument panel" for a single agent — full telemetry without leaving the control room.

### 4.2 Event sources for narrative

The narrative view MUST render from:

- `:control-plane/status-changed` — agent state transitions
- `:agent/tool-call` — individual tool invocations (file read, grep, edit, command)
- `:agent/thinking` — when the agent is in LLM inference
- `:agent/subagent-spawned` — nested agent creation
- `:control-plane/decision-submitted` — agent requesting human input
- Budget events from the agent role config

### 4.3 Relationship to PR monitor narrative

The PR monitor loop (`:pr-monitor/*` events) is a specific case of agent narrative. When the monitor loop is active
for a PR, selecting that PR SHOULD show the monitor's activity stream: comments received, classifications made, fixes
pushed, replies posted, budget status. This is the Loop 1 narrative rendered within the Loop 2/3 surface.

## 5. Interaction tier separation

### 5.1 Principle

Not all surfaces are equal for interaction. Latency, input model, and attention context determine what level of
control a surface supports effectively.

### 5.2 Tier assignment

| Surface                                  | OCI capability               | Rationale                                                                      |
| ---------------------------------------- | ---------------------------- | ------------------------------------------------------------------------------ |
| Native app (Rust core + Swift/GTK shell) | CONTROL                      | Low latency, rich rendering, persistent session, full keyboard + pointer input |
| Rust TUI (Rust core + ratatui)           | CONTROL (degraded rendering) | Same Rust core, terminal renderer; for SSH/headless/tmux                       |
| Web dashboard                            | OBSERVE (+ limited ADVISE)   | Higher latency, browser context-switching, better for fleet overview           |
| API                                      | CONTROL                      | Programmatic, no latency constraint, used by automation                        |

### 5.3 Web dashboard scope constraint

The web dashboard MUST be optimized for fleet-scale observation: aggregate metrics, cross-project views, trend
analysis, compliance reporting. It SHOULD support lightweight advisory actions (commenting, flagging). It SHOULD NOT
attempt to replicate the embedded agent CLI or real-time decision resolution — these require the low-latency,
persistent-session characteristics of the native app.

### 5.4 Native app as primary CONTROL surface

The native app (§2A) is the primary CONTROL surface. It ships the full experience: embedded agent CLI, agent
narrative with syntax-highlighted code, resizable multi-pane layout, decision resolution with rich input, budget
visualization with proper charts.

The Rust TUI provides the same CONTROL capability with degraded rendering (no syntax highlighting, no graphical DAGs,
no resizable panes). It is the right choice when the operator is SSH'd into a server or running in a headless
container. It is not the right choice for daily dogfooding.

### 5.5 Both surfaces share the Rust core — identically

The native app and Rust TUI use the same Rust core crates. The state manager, projections, CLI dispatch, event
client, and control plane client are identical code. Only the renderer differs:

- Rust TUI: `ratatui` (in-process, direct crate dependency)
- Native app: C-ABI facade → Swift/SwiftUI (or GTK, WinUI)

There is no state divergence between surfaces because there is one state manager implementation.

### 5.6 Single binary distribution

The Rust TUI and Rust core compile to a single static binary (`miniforge-console`). No JVM, no runtime dependencies.
The macOS native app ships as a `.app` bundle with the Rust dylib embedded in Frameworks/.

```
miniforge-console          # Rust TUI binary (Linux/macOS/Windows)
Miniforge Console.app/     # macOS native app
  Contents/
    Frameworks/
      libminiforge_control.dylib
    MacOS/
      Miniforge Console    # Swift executable
```

## 6. Extended intervention vocabulary

### 6.1 New interventions (amending N5-delta-1 §7)

| Intervention       | Target                    | Effect                                          |
| ------------------ | ------------------------- | ----------------------------------------------- |
| `command`          | System (via embedded CLI) | Spawns agent session to execute intent          |
| `query`            | System (via embedded CLI) | Analyzes supervisory state and returns response |
| `resolve-decision` | Agent (blocked)           | Delivers decision resolution, unblocks agent    |
| `adjust-budget`    | Agent (active)            | Modifies token/cost budget for running session  |
| `terminate-agent`  | Agent (any non-terminal)  | Terminates agent session                        |

### 6.2 Decision resolution UX

When an agent submits a decision (`:control-plane/decision-submitted`), the TUI MUST:

1. Surface it as a `:critical` attention item (per N5-delta-1 §5.1)
2. When the operator navigates to the item, show:
   - The agent's name, vendor, and current task
   - The decision summary (what the agent is asking)
   - Structured options (if the decision provides them)
   - Free-form input (if the decision accepts it)
3. On resolution, call `control-plane/resolve-and-deliver!`
4. Show the agent's state transition from `:blocked` to `:executing` in real time

This MUST be completable without leaving the TUI. No browser, no terminal switch, no file editing.

## 7. Latency requirements

### 7.1 Input-to-feedback

The TUI/native MUST provide:

| Action                 | Maximum latency to visible feedback |
| ---------------------- | ----------------------------------- |
| Keystroke echo         | 16ms (frame time)                   |
| Navigation (j/k, tab)  | 16ms                                |
| Command submission     | 100ms to acknowledgment             |
| Intervention execution | 200ms to state change visible       |
| Agent status update    | 1s from event emission to render    |
| Workflow phase change  | 1s from event emission to render    |

### 7.2 Web dashboard (for comparison)

Web dashboard targets are relaxed:

| Action              | Maximum latency                  |
| ------------------- | -------------------------------- |
| Page load           | 2s                               |
| Filter/search       | 500ms                            |
| Status update (SSE) | 3s from event emission to render |

This latency gap is WHY the web dashboard is OBSERVE-tier and the TUI/native is CONTROL-tier.

## 8. Backend delegation

### 8.1 Principle

The embedded agent CLI MUST NOT contain its own LLM client. It MUST delegate to the Miniforge agent system, which
routes to the configured backend via the model catalog and role configuration.

### 8.2 Resolution path

```
Operator input (embedded CLI)
  → Determine agent role (from intent or explicit)
  → Look up role config (model, temperature, budget, capabilities)
  → Look up model in catalog → resolve backend (:claude, :codex, :gemini, :ollama)
  → Spawn agent session via control plane
  → Agent executes using configured backend
  → Events flow through event stream
  → TUI renders supervisory state
```

### 8.3 Backend transparency

The TUI SHOULD display which backend is handling a given agent session (in the agent sessions zone and narrative view).
This is part of the "nuclear plant instrument panel" — the operator should know which engine is running.

### 8.4 Backend override

The operator MAY override the backend for a specific command via the embedded CLI:

```
:run auth-flow --model claude-opus-4-6
:run auth-flow --backend codex
```

Overrides MUST be recorded in the agent session metadata and visible in evidence.

## 9. Monitor mode amendments

### 9.1 Zone additions

The monitor mode layout (N5-delta-1 §8.1) is amended to include:

```
┌─────────────────────────────────────────────────────────────┐
│ AGENT SESSIONS          │ WORKFLOW TICKER                    │
│ 🤖 Claude Code: auth    │ ● auth-flow  implement  3m        │
│    executing PR review  │ ✓ fix-login  complete   12m       │
│ 🤖 Codex: data-pipe     │ ✗ add-metrics failed              │
│    blocked: decision    │                                    │
├─────────────────────────┴────────────────────────────────────┤
│ PR FLEET / TRAIN                                             │
│ ▓▓▓░░ 3/5 merged  Next: #247 (CI passing, ● monitoring)    │
│ #251 ⊘ budget exhausted — escalated                         │
├──────────────────────────────────────────────────────────────┤
│ ▲ ATTENTION: [!] Codex blocked: needs decision  [!] #251    │
├──────────────────────────────────────────────────────────────┤
│ : _                                              [embedded CLI]
└──────────────────────────────────────────────────────────────┘
```

The embedded CLI bar sits at the bottom, always available but not intrusive. The agent sessions zone is promoted
from "when control plane active" (N5-delta-1 §8.1) to a persistent top-level zone — because in the meta-meta loop,
agent sessions ARE the primary subject of observation.

### 9.2 Narrow-width adaptation (60 columns)

At 60 columns, the embedded CLI bar remains. Agent sessions collapse to a single summary line:

```
┌──────────────────────────────────────────────────────┐
│ Agents: 2 active, 1 blocked │ Workflows: 1 running  │
├──────────────────────────────────────────────────────┤
│ ● auth-flow  implement  3m  (Claude Code)           │
│ ✗ add-metrics failed  (Codex, blocked)              │
├──────────────────────────────────────────────────────┤
│ PRs: ▓▓▓░░ 3/5  Next: #247                         │
├──────────────────────────────────────────────────────┤
│ [!] Codex blocked  [!] #251 escalated               │
├──────────────────────────────────────────────────────┤
│ : _                                                  │
└──────────────────────────────────────────────────────┘
```

## 10. Conformance

### 10.1 Embedded agent CLI conformance

1. Commands issued via embedded CLI MUST produce standard agent sessions in the control plane registry
2. Agent sessions MUST use configured backend from model catalog, not a hardcoded provider
3. CLI output MUST flow through supervisory zones, not bypass them
4. Embedded CLI MUST be available in TUI and native app; web dashboard MAY omit it

### 10.2 Interaction tier conformance

1. TUI and native app MUST support CONTROL-tier interactions (interventions, decision resolution, embedded CLI)
2. Web dashboard MUST support OBSERVE-tier; MAY support limited ADVISE-tier
3. All surfaces MUST render from the same supervisory entity model

### 10.3 Latency conformance

1. TUI/native MUST meet latency targets in §7.1
2. Web dashboard MUST meet latency targets in §7.2
3. Agent status updates MUST propagate from event emission to render within 1s (TUI/native) or 3s (web)

### 10.4 Narrative view conformance

1. Agent narrative MUST be accessible via drill-down from agent session row
2. Narrative MUST show current activity, recent actions, decision state, and budget
3. PR monitor narrative MUST be accessible via drill-down from PR row when monitor is active

## 11. Superseded

The following Clojure TUI components are superseded by the Rust core + renderers:

| Component                           | Replacement                              |
| ----------------------------------- | ---------------------------------------- |
| `tui-engine` (Clojure/Lanterna)     | `ratatui` + `crossterm` (Rust)           |
| `tui-views` (Clojure)               | Rust state manager + projection builders |
| `tui-views/file-subscription`       | Rust event-client crate                  |
| `tui-views/update` (event handlers) | Rust state manager event handlers        |
| `cli/tui.clj` (entry point)         | `miniforge-console` binary               |

The Clojure TUI code is not deleted — it remains as reference for behavior parity. But new development targets the
Rust implementation exclusively.

## 12. Deferred

| Concept                                 | Reason for deferral                                                   |
| --------------------------------------- | --------------------------------------------------------------------- |
| Multi-agent orchestration from CLI      | Single-command → single-session sufficient for v1                     |
| Voice input for embedded CLI            | Input model is keyboard-first                                         |
| Collaborative multi-operator sessions   | Single-operator sufficient for dogfooding                             |
| Plugin system for custom zones          | Monitor layout stabilization first                                    |
| Linux/Windows native shells             | macOS first (dogfooding platform); Rust core + TUI are portable day 1 |
| Thesium/Miniforge framework convergence | Acknowledged (§2A.6) but premature to formalize                       |
