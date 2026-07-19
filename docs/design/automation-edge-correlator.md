<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Design RFC — Automation Edge Correlator

- **Status:** Draft
- **Date:** 2026-05-17
- **Related:** miniforge-control [N5-delta-3 §6.4](https://github.com/miniforge-ai/miniforge-control/blob/main/specs/normative/N5-delta-3-bare-agent-supervision.md)
  (routing-event visibility, primary witness-surface principle);
  miniforge-control [AA-2 PR #66](https://github.com/miniforge-ai/miniforge-control/pull/66) (Rust
  `AutomationEdge` entity, `:supervisory/automation-edge-upserted` event type)
- **Consumer:** Rust `miniforge-control` core, which already has the
  `AutomationEdge` entity in `contracts/crates/supervisory-entities/` and a
  state-manager handler for the upsert event — waiting for a producer
- **Wedge:** N5-delta-3 §1.3 pain point #1, "operator-as-message-bus." The
  ~448 manual `:pr/merged` acks and ~80 `respond-to-comments` directives the
  operator does per closed-loop run go away when Miniforge workflows handle
  the routing autonomously. The native app needs to **render** that
  autonomous handling so the operator can trust it; without this RFC, the
  Rust `AutomationEdge` table sits empty and the witness surface is blank.

## Why

The N13 closed-loop PR pipeline (standards-reviewer, comment-response-agent,
auto-trigger, listener-registry, resume-dispatcher) has been absorbing the
operator-as-message-bus role for weeks. When PR #857 merges, resume-
dispatcher fires the next workflow; when review comments arrive, comment-
response-agent applies the fix; when CI fails, fix-loop fires.

The operator currently has no surface that says **"Miniforge handled this for
you"**. They can read the raw event stream, but the routing causality is
implicit — `:pr/merged` happens, then a `:workflow/started` happens, and the
operator has to infer they're related from timing + correlation IDs.

The Rust core (`miniforge-control`) already has the consumer half: `AutomationEdge`
entity in `contracts/`, projection slot in `SupervisoryState.automation_edges`,
`apply_automation_edge` handler, FFI subscribe payload includes the edges. What
it consumes does not exist on the wire — no Clojure producer emits
`:supervisory/automation-edge-upserted` events.

This RFC adds that producer.

## What this RFC does not do

- **Does not add new routing logic.** The N13 pipeline's existing handlers
  (resume-dispatcher, comment-response-agent, etc.) keep doing what they do.
  The correlator **observes** their outputs and emits one new event type
  summarizing each causal edge — it does not re-implement or change routing.
- **Does not change the on-wire shape of any existing event.** Only adds a
  new event type.
- **Does not address operator interventions** (`:edge/suppress`, etc.) — those
  flow through the existing intervention-vocabulary surface from N5-delta-3
  §6.3, separate from the correlator.

## Architecture

Place the correlator as a **new component** at `components/automation-edge-correlator/`,
not as a subsystem of supervisory-state. Two reasons:

1. supervisory-state's accumulator is already large and serves a different
   responsibility (canonical entity table from fine-grained events). Adding
   a stateful correlator that also subscribes-and-emits would blur the brick.
2. The correlator has its own state (pending-trigger map) and emission
   discipline (ignore-own-events to prevent recursion). It's a sibling
   producer to supervisory-state's `:supervisory/*-upserted` snapshot
   emitter, not a child of it.

Component layout:

```
components/automation-edge-correlator/
├── deps.edn
├── src/ai/miniforge/automation_edge_correlator/
│   ├── interface.clj         (Layer 0: public API — start!, stop!, attach!)
│   ├── core.clj              (Layer 1: lifecycle + subscription wiring)
│   ├── triggers.clj          (Layer 0: trigger-event classification)
│   ├── correlator.clj        (Layer 0: pure trigger → edge logic)
│   ├── emitter.clj           (Layer 0: envelope construction)
│   └── schema.clj            (Layer 0: malli schema for AutomationEdge wire form)
└── test/ai/miniforge/automation_edge_correlator/
    ├── triggers_test.clj
    ├── correlator_test.clj
    └── integration_test.clj
```

Strata follow the existing supervisory-state convention. Layer 0 is
pure-function: trigger classification, correlation logic, schema. Layer 1
is the lifecycle wrapper that subscribes to the event stream and pumps
events through the pure layers.

## RoutingTriggerKind taxonomy

Trigger events the correlator watches for, mapped to the Rust contract's
`RoutingTriggerKind` enum (from `miniforge-control/contracts/.../entities.rs`):

| Trigger event type (wire form) | Provenance | Rust `RoutingTriggerKind` |
|---|---|---|
| `:pr/merged` | existing | `PrMerged` |
| `:pr-monitor/review-comments-arrived` | new — emitted by pr-monitor when GH webhook delivers review comments | `ReviewCommentsArrived` |
| `:pr-monitor/ci-failed` | new — emitted by pr-monitor when CI status transitions to failure | `CiFailed` |
| `:standards-review/posted` | new — emitted by standards-reviewer when a review comment lands on a PR | `StandardsReviewArrived` |
| `:workflow/completed` | existing | `WorkflowCompleted` |
| `:gate/passed`, `:gate/failed` | existing | `GateFired` |

The three "new" trigger events are emitted by existing N13 components but
not yet declared as first-class supervisory events. Adding them is a small
sibling task to this RFC — either land in the same PR or in a follow-on.

## Edge lifecycle + status state machine

```
                           ┌─────────────┐
       trigger observed    │             │   handler workflow completes
       ────────────────►   │  :observed  │   ──────────────────────────►  :handled
                           │             │
                           └──────┬──────┘
                                  │  handler workflow fails
                                  │  ──────────────────────────►  :failed
                                  │
                                  │  no handler within suppression window
                                  │  OR handler explicitly declared no-handler
                                  │  ──────────────────────────►  :needs-operator
                                  │
                                  │  operator emits :edge/suppress
                                  │  ──────────────────────────►  :suppressed
```

Transitions:

- **`:observed`** — Correlator sees a trigger event. Emits the initial edge
  with `:edge/status :observed`, no `:handled-by-workflow-run-id` yet, derives
  `affected-pr-ids` and `affected-agent-session-ids` from the trigger payload.
- **`:handled`** — Correlator sees a `:workflow/completed` event whose
  workflow correlated to this trigger. Updates the edge to
  `:edge/status :handled`, populates `:handled-by-workflow-run-id`.
- **`:failed`** — Correlator sees `:workflow/failed` for the correlated
  workflow. Edge transitions to `:edge/status :failed`,
  `:operator-action-required true`.
- **`:needs-operator`** — No handler started within a configurable window
  (default 5 minutes), OR a handler declared `:no-handler-available` (this
  is a new emission point in resume-dispatcher when the routing edge has no
  configured workflow). Edge transitions, `:operator-action-required true`,
  `:fallback-intervention` populated with the intervention keyword the
  operator should run.
- **`:suppressed`** — Operator emits `:edge/suppress` (an intervention from
  N5-delta-3 §6.3). Correlator transitions the edge.

## Idempotency

`:edge/id` is a deterministic UUIDv5 derived from **`trigger-event-id`
alone** — not from `(trigger-event-id, handler-workflow-id)`. The reason:
the edge transitions from `:observed` (no handler yet) through `:handled`
(handler workflow correlated) over its lifetime, and the entity table must
update the **same row** on each transition. If the id depended on the
handler-workflow-id, the row would change keys when the handler was
discovered (nil → workflow-id), breaking the "each trigger event produces
exactly one edge over its lifetime" invariant and leaking the initial
`:observed`-keyed row.

The handler workflow id lives in the dedicated `:handled-by-workflow-run-id`
field. Status transitions upsert the same `:edge/id`.

Two observations of the same trigger produce the same `:edge/id`; replay
never duplicates edges.

`:edge/idempotency-key` is the trigger-event-id stringified — exposed for
downstream consumers (notification arbiter, dashboard) that want to dedup
across producer/consumer boundaries per N5-delta-3 §6.6.

## Correlation discipline

The correlator maintains an in-memory map of `trigger-event-id → edge-id` so
it can transition existing edges when a handler workflow completes. The map
is bounded — entries time out after the suppression window (default 5
minutes) OR when the edge transitions to a terminal status (`:handled`,
`:failed`, `:suppressed`).

Cross-restart: the correlator's in-memory map is rebuilt on startup from
the event stream replay (per existing supervisory-state pattern). It re-
emits `:supervisory/automation-edge-upserted` events for every edge it
reconstructs — consumers (Rust core) dedup via `:edge/id`.

## What "correlated workflow" means

A handler workflow is "correlated" to a trigger when:

1. The workflow's `:workflow/started` event carries an explicit
   `:routing/trigger-event-id` field pointing at the trigger. This is the
   preferred path — the resume-dispatcher (and friends) attach the trigger
   ID when they fire a handler.
2. **Fallback:** the workflow starts within a configurable correlation
   window (default 60s) AND its `:workflow/spec` matches a registered
   handler-spec for the trigger kind. Lossy but covers handlers that don't
   yet carry the explicit ID. Log a warn when this fallback fires so we can
   audit and migrate to explicit IDs.

The N13 components (resume-dispatcher, comment-response-agent,
standards-reviewer) need a small change: every handler-workflow start
includes `:routing/trigger-event-id` in its `:workflow/started` payload. This
is a one-line edit in each, queued as part of this workstream.

## Affected PRs and agents

The trigger event carries enough payload to populate `:edge/affected-pr-ids`
and `:edge/affected-agent-session-ids`:

- `:pr/merged` carries `:pr/repo` + `:pr/number` — one affected PR.
- `:pr-monitor/review-comments-arrived` carries `:pr/repo` + `:pr/number` +
  `:comments/agent-session-id` (the agent owning the PR per the PR↔agent
  index, AA-2).
- `:standards-review/posted` carries the PR + the affected workflow.
- `:workflow/completed` carries `:workflow/id` + (via correlation) the PRs
  the workflow owns.

When the trigger doesn't carry enough payload to identify affected entities,
the correlator emits the edge with empty `affected-*` vectors and logs a
warn. Downstream consumers handle the empty case gracefully (already in
AA-2's projection logic).

## Schema

`schema.clj` defines the wire form, matching the Rust contract:

```clojure
(def routing-trigger-kinds
  [:pr-merged :review-comments-arrived :ci-failed
   :standards-review-arrived :workflow-completed :gate-fired])

(def automation-edge-statuses
  [:observed :handled :failed :needs-operator :suppressed])

(def AutomationEdge
  [:map
   [:edge/id                              :id/uuid]
   [:edge/spec-id          {:optional true} [:maybe :id/uuid]]
   [:edge/trigger-event-id                :id/uuid]
   [:edge/trigger-kind                    (into [:enum] routing-trigger-kinds)]
   [:edge/handled-by-workflow-run-id {:optional true} [:maybe :id/uuid]]
   [:edge/affected-pr-ids  {:optional true} [:vector [:tuple :string :common/non-neg-int]]]
   [:edge/affected-agent-session-ids {:optional true} [:vector :id/uuid]]
   [:edge/status                          (into [:enum] automation-edge-statuses)]
   [:edge/operator-action-required {:optional true} boolean?]
   [:edge/fallback-intervention {:optional true} [:maybe keyword?]]
   [:edge/idempotency-key                 string?]
   [:edge/occurred-at                     :common/timestamp]
   [:edge/updated-at                      :common/timestamp]])
```

Mirrors the Rust contract one-to-one. Open map (additional keys pass
through).

## Sequencing (the burndown)

| Step | Status | Deliverable |
|---|---|---|
| N15-1 | ⬜ next | New component scaffold + `schema.clj` + `triggers.clj` (pure trigger classification). Unit tests on the classification function: each input event → expected `RoutingTriggerKind` or `nil`. |
| N15-2 | ⬜ | `correlator.clj` (pure trigger → edge logic, status transitions, idempotency). Unit tests cover the state machine: `:observed → :handled`, `:observed → :failed`, `:observed → :needs-operator` on timeout, `:* → :suppressed`. |
| N15-3 | ⬜ | `emitter.clj` + `core.clj` (lifecycle, event-stream subscription). Integration test against an in-memory event stream: emit a trigger, observe the `:observed` edge; emit a correlated `:workflow/completed`, observe the `:handled` transition. |
| N15-4 | ⬜ | `:routing/trigger-event-id` field added to resume-dispatcher / comment-response-agent / standards-reviewer / pr-monitor's handler-workflow `:workflow/started` emissions. One-line edits per producer; sibling PR. |
| N15-5 | ⬜ | New trigger event types declared: `:pr-monitor/review-comments-arrived`, `:pr-monitor/ci-failed`, `:standards-review/posted`. Schema added to `event-stream/schema.clj`; emission sites added to existing components. |
| N15-6 | ⬜ | Interface re-export from `automation-edge-correlator/interface.clj`. Wired into the canonical event-stream attach pattern. Polylith `bb poly:check` clean. |
| N15-7 | ⬜ | Optional: dashboard / TUI rendering for the new edge entity. Deferred — Rust core's TUI already projects AutomationEdge entities into state; the upstream Clojure TUI may not need its own renderer if the surface is going native-first. |

Each step is one PR. Total expected merge surface ~6 PRs.

## Acceptance for the workstream

- `:supervisory/automation-edge-upserted` events appear on
  `~/.miniforge/events/<run-id>/` whenever a trigger fires.
- Each trigger event produces exactly one edge over its lifetime (status
  transitions update the same `:edge/id`).
- Replay of an existing event-stream prefix reconstructs the same edges
  (deterministic UUIDv5).
- Rust `miniforge-control` core's `SupervisoryState.automation_edges` map
  populates with real data on a live dogfood run.
- All existing tests pass. New correlator tests are green.
- `bb poly:check` clean.

## Open questions

1. **Suppression-window default.** 5 minutes was the proposed default. Some
   long-running handlers (e.g. a standards-reviewer that takes a fix loop
   to converge) may exceed that. Configurable per trigger kind? Or globally
   tunable? Lean: globally tunable for v1, per-kind in a follow-on.
2. **Edge garbage collection.** Terminal-status edges (`:handled`, `:failed`,
   `:suppressed`) stay in the entity table indefinitely. At what point
   does the supervisor prune them? Lean: never prune in v1; let the Rust
   core's SQLite event log handle long-term storage via AA-9's retention
   policy.
3. **Cross-spec triggers.** A single `:workflow/completed` could legitimately
   handle multiple triggers (e.g. a fix workflow that addresses both a CI
   failure AND a standards-review comment). The current design emits
   separate edges keyed on trigger-id; the workflow appears in both edges'
   `:handled-by-workflow-run-id`. Acceptable; document the multi-edge case.

## References

- miniforge-control `N5-delta-3-bare-agent-supervision.md` §6.4 (Routing-event
  visibility / primary witness-surface principle), §6.6 (AutomationEdge
  projection conformance)
- miniforge-control AA-2 PR #66 (Rust `AutomationEdge` entity + state
  projection)
- N13 closed-loop PR pipeline — `components/pr-lifecycle/src/...`
  resume-dispatcher, comment-response-agent, standards-reviewer
- Operator-memory `feedback_no_backwards_compat` — this is a new event type
  emission; no migration of existing events needed.
