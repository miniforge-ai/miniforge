<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N5 Delta 4 — Automation Edge Correlator

- **Spec ID:** `N5-delta-automation-edge-correlator-v1`
- **Version:** `0.1.0-draft`
- **Status:** Draft
- **Date:** 2026-05-17
- **Amends:** N5-delta-supervisory-control-plane-v1 (§3.1 v1 entities, §3.4 supervisory-state component)
- **Amends:** N5-delta-evidence-artifact-task-decision-pack-v1 (entity set extended with `AutomationEdge`)
- **Related:** N3 (event stream), N8 (observability control), N9 (external PR integration);
  design RFC `docs/design/automation-edge-correlator.md`;
  miniforge-control consumer contract `contracts/crates/supervisory-entities/`
  (`AutomationEdge` struct + `:supervisory/automation-edge-upserted` handler)

## 1. Purpose

N5-δ1 (supervisory control plane) and N5-δ3 (evidence/artifact/task/decision/pack entities) make
**workflow state** observable. Neither makes **routing causality** observable: when a `:pr/merged`
event triggers a downstream workflow, the operator has no surface that says *"Miniforge handled
this for you."* The causal edge between the trigger event and the handler workflow is implicit —
the operator must infer the link from timing + correlation IDs in the raw event stream.

This delta defines the `AutomationEdge` entity, its lifecycle, and the producer component
(`components/automation-edge-correlator`) that materializes routing causality as first-class
supervisory state.

### 1.1 Operator-as-message-bus elimination

The N5-δ3 wedge ("operator-as-message-bus") quantified the manual routing burden as ~448
`:pr/merged` acks and ~80 `respond-to-comments` directives per closed-loop run. The N13 closed-
loop PR pipeline (resume-dispatcher, comment-response-agent, standards-reviewer, auto-trigger,
listener-registry) has absorbed that role. This delta closes the witness-surface gap: every
autonomous routing decision MUST surface as an `AutomationEdge` so the operator can audit, trust,
and selectively override automation.

### 1.2 What this delta adds

| Capability                                  | Backs                                      | Kind                              |
|---------------------------------------------|--------------------------------------------|-----------------------------------|
| `AutomationEdge` entity                     | N5-δ3 §3.1 v1-renderable entity set        | Causal-edge projection (open map) |
| `RoutingTriggerKind` taxonomy               | producer + consumer wire contract          | Closed enum (six values v1)       |
| `automation-edge-statuses` state machine    | edge lifecycle                             | Closed enum (five values v1)      |
| `:supervisory/automation-edge-upserted`     | N3 event stream                            | New event type                    |
| `components/automation-edge-correlator`     | producer brick                             | New component (sibling of `supervisory-state`) |
| `:routing/trigger-event-id` envelope field  | handler-workflow `:workflow/started` payload | Added correlation handle        |
| Trigger event additions                     | N3 event stream                            | `:pr-monitor/review-comments-arrived`, `:pr-monitor/ci-failed`, `:standards-review/posted` |

### 1.3 What this delta does NOT change

- **N5-δ1 §3.4 invariant 6** remains. `supervisory-state` is still the sole emitter of
  `:supervisory/*-upserted` events for the entities it owns. The `automation-edge-correlator`
  emits `:supervisory/automation-edge-upserted` and ONLY that snapshot event type; it does not
  emit upserts for any other entity.
- **No re-implementation of routing.** The N13 pipeline's handlers continue to dispatch the same
  workflows on the same triggers. This delta adds a passive observer that **classifies and
  correlates**; it does not gate, redirect, or augment routing decisions.
- **No change to the on-wire shape of any existing event.** Only new event types are added.
- **No new intervention surface.** Operator overrides on edges (suppress / unsuppress / re-route)
  flow through the existing intervention vocabulary defined in N5-δ1 §7 and surfaced via
  N5-delta-supervisory-control-plane-v1 §7.

### 1.4 Component placement rationale

The correlator MUST be a sibling Polylith component to `supervisory-state`, not a subsystem of
it. Two reasons make this normative:

1. **Brick responsibility.** `supervisory-state`'s accumulator is the canonical fine-grained →
   snapshot rollup for entities sourced from a single producing subsystem. The correlator does
   cross-event causal inference (trigger ↔ handler-workflow) and maintains its own pending-edge
   index. Folding cross-event logic into the entity accumulator would blur invariant 6.
2. **Recursion discipline.** The correlator subscribes to event types that include its own
   downstream consequences (a handler workflow it correlates is itself observable as
   `:workflow/started`). It MUST filter its own emissions out of the input stream. Keeping this
   discipline visible at the brick boundary keeps it auditable; embedding it inside
   `supervisory-state` would hide it.

## 2. Entity shape — `AutomationEdge`

Added to the N5-δ1 §3.1 v1-renderable entity set. Open-map rule applies: producers MAY include
additional fields; consumers MUST preserve unknowns for round-trip.

```clojure
[:map {:registry registry, :closed false}
 [:edge/id                            :id/uuid]
 [:edge/spec-id            {:optional true} [:maybe :id/uuid]]
 [:edge/trigger-event-id              :id/uuid]
 [:edge/trigger-kind                  :edge/trigger-kind]    ; closed enum — §2.1
 [:edge/handled-by-workflow-run-id {:optional true} [:maybe :id/uuid]]
 [:edge/affected-pr-ids   {:optional true} [:vector [:tuple :string :common/non-neg-int]]]
 [:edge/affected-agent-session-ids {:optional true} [:vector :id/uuid]]
 [:edge/status                        :edge/status]          ; closed enum — §2.2
 [:edge/operator-action-required {:optional true} boolean?]
 [:edge/fallback-intervention {:optional true} [:maybe keyword?]]
 [:edge/idempotency-key               string?]
 [:edge/occurred-at                   :common/timestamp]
 [:edge/updated-at                    :common/timestamp]]
```

Field obligations:

- **`:edge/id`** — deterministic UUIDv5 derived from `:edge/trigger-event-id` alone. See §2.3.
- **`:edge/trigger-event-id`** — the event id of the trigger event that opened this edge. The
  correlator MUST use this field as the sole key for the in-memory pending-edge index.
- **`:edge/trigger-kind`** — closed enum per §2.1. Producers MUST NOT emit values outside the
  enum; consumers MAY treat unknown values as an opaque keyword for forward compatibility, but
  the producer contract is closed.
- **`:edge/handled-by-workflow-run-id`** — `nil` while `:edge/status = :observed`; populated on
  transition to `:handled` or `:failed`.
- **`:edge/affected-pr-ids`** — tuples of `[repo number]`, derived from the trigger payload per
  §3.4. MAY be empty (with a warn log) when the trigger payload does not carry PR identity.
- **`:edge/affected-agent-session-ids`** — agent sessions causally affected by the routing
  decision (e.g. the agent that owns the merged PR). MAY be empty.
- **`:edge/status`** — closed enum per §2.2. State machine in §2.4.
- **`:edge/operator-action-required`** — `true` for terminal failure / `:needs-operator` states
  the operator must act on; `false` otherwise. Surfaces in attention queues.
- **`:edge/fallback-intervention`** — when status is `:needs-operator`, the intervention keyword
  (per N5-δ1 §7 vocabulary) the operator should run. `nil` otherwise.
- **`:edge/idempotency-key`** — stringified `:edge/trigger-event-id`. Exposed for downstream
  consumers (notification arbiter, dashboard) that dedup across producer/consumer boundaries
  per N5-δ3 §6.6.
- **`:edge/occurred-at`** — timestamp of the trigger event.
- **`:edge/updated-at`** — wall-clock of the most-recent status transition.

### 2.1 `:edge/trigger-kind` — closed enum

```clojure
(def routing-trigger-kinds
  [:pr-merged
   :review-comments-arrived
   :ci-failed
   :standards-review-arrived
   :workflow-completed
   :gate-fired])
```

Mapping from trigger event type (wire form) to enum value:

| Trigger event type                       | `:edge/trigger-kind`         | Provenance |
|------------------------------------------|------------------------------|------------|
| `:pr/merged`                             | `:pr-merged`                 | existing   |
| `:pr-monitor/review-comments-arrived`    | `:review-comments-arrived`   | new (§4.2) |
| `:pr-monitor/ci-failed`                  | `:ci-failed`                 | new (§4.2) |
| `:standards-review/posted`               | `:standards-review-arrived`  | new (§4.2) |
| `:workflow/completed`                    | `:workflow-completed`        | existing   |
| `:gate/passed` / `:gate/failed`          | `:gate-fired`                | existing   |

Producers MAY NOT emit additional enum values without a spec bump. Consumers (Rust
`RoutingTriggerKind` enum in `contracts/crates/supervisory-entities/`) MUST match the value set
exactly.

### 2.2 `:edge/status` — closed enum

```clojure
(def automation-edge-statuses
  [:observed :handled :failed :needs-operator :suppressed])
```

State semantics — see §2.4 for the transition machine.

### 2.3 Idempotency invariant

`:edge/id` MUST be a deterministic UUIDv5 derived from `:edge/trigger-event-id` and ONLY that
field. Implementations MUST NOT include the handler workflow id, status, or any mutable field in
the id derivation.

The rationale is the entity-table contract: each edge transitions from `:observed` (no handler
yet) through `:handled` (handler workflow correlated) over its lifetime, and the consumer's
entity table MUST update the same row on each transition. If the id depended on the handler-
workflow-id, the row would change keys when the handler was discovered (nil → workflow-id),
violating two invariants:

1. **One-edge-per-trigger.** Each trigger event produces exactly one edge over its lifetime.
2. **No orphan rows.** The initial `:observed`-keyed row would leak in the consumer's table when
   the handler-discovered key replaced it.

UUIDv5 namespace MUST be a stable per-deployment namespace UUID (recommended: a constant defined
in `components/automation-edge-correlator/src/.../schema.clj`). Implementations MUST NOT use the
nil UUID or the DNS / URL / OID / X500 namespaces.

Two observations of the same trigger event id MUST produce the same `:edge/id`. Replay of an
event-stream prefix MUST NOT duplicate edges. Producers re-emitting an upsert with unchanged
fields MUST produce byte-identical payloads (per N5-δ3 §3.6 shared contract).

### 2.4 Edge lifecycle — status state machine

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
                                  │  operator emits :edge/suppress intervention
                                  │  ──────────────────────────►  :suppressed
```

Transition obligations:

- **`:observed`** — initial state on trigger observation. The correlator MUST emit the edge with
  `:edge/handled-by-workflow-run-id` absent, `:edge/operator-action-required false`,
  `:edge/affected-pr-ids` / `:edge/affected-agent-session-ids` derived from the trigger payload
  per §3.4.
- **`:observed → :handled`** — the correlator observes a `:workflow/completed` event whose
  workflow correlated to this trigger (per §3.5 "correlated workflow" rules). The correlator
  MUST update the edge with `:edge/handled-by-workflow-run-id` populated,
  `:edge/operator-action-required false`, and `:edge/updated-at` set to the
  `:workflow/completed` event's timestamp.
- **`:observed → :failed`** — the correlator observes a `:workflow/failed` event for the
  correlated workflow. The edge MUST transition to `:failed` with
  `:edge/operator-action-required true`. Implementations SHOULD populate
  `:edge/fallback-intervention` when a remediation intervention is defined for the
  trigger-kind / failure-class pair.
- **`:observed → :needs-operator`** — no handler workflow is observed within the suppression
  window (default 5 minutes; see §6), OR a handler explicitly declares `:no-handler-available`
  (a new emission point in `resume-dispatcher` when the routing edge has no configured
  workflow). The edge MUST transition to `:needs-operator` with
  `:edge/operator-action-required true` and `:edge/fallback-intervention` populated with the
  intervention keyword for the operator's manual disposition.
- **`{:observed, :handled, :failed, :needs-operator} → :suppressed`** — the operator emits an
  `:edge/suppress` intervention (per N5-δ1 §7). The correlator MUST transition the edge,
  preserve `:edge/handled-by-workflow-run-id` if previously set, and set
  `:edge/operator-action-required false`. A suppressed edge MUST NOT transition further.

`:handled`, `:failed`, and `:suppressed` are terminal. The correlator MUST NOT re-transition a
terminal edge except via an explicit operator intervention (`:edge/unsuppress`, when the
intervention vocabulary defines one — out of scope for v1).

## 3. Producer contract — `components/automation-edge-correlator`

A new Polylith component. Sibling to `components/supervisory-state` (per §1.4). Subscribes to
the same event stream and emits `:supervisory/automation-edge-upserted` events per §4.

### 3.1 Component structure

The component MUST follow the existing supervisory-state stratification convention:

```
components/automation-edge-correlator/
├── deps.edn
├── src/ai/miniforge/automation_edge_correlator/
│   ├── interface.clj         ; Layer 0 — public API: start!, stop!, attach!
│   ├── core.clj              ; Layer 1 — lifecycle + subscription wiring
│   ├── triggers.clj          ; Layer 0 — trigger event classification (pure)
│   ├── correlator.clj        ; Layer 0 — pure trigger → edge logic + state machine
│   ├── emitter.clj           ; Layer 0 — envelope construction
│   └── schema.clj            ; Layer 0 — malli schema for AutomationEdge wire form
└── test/ai/miniforge/automation_edge_correlator/
    ├── triggers_test.clj
    ├── correlator_test.clj
    └── integration_test.clj
```

Layer 0 files MUST be pure-function (no I/O, no system clock except as injected). Layer 1 is the
only file allowed to perform I/O (event-stream subscribe, emit).

### 3.2 Lifecycle

The component MUST expose:

- **`(start! deps)`** — begins consuming the event stream and emits edges. Returns an opaque
  handle.
- **`(stop! handle)`** — releases the subscription and flushes any pending in-memory edges (per
  §3.6 cross-restart).
- **`(attach! handle event-stream)`** — alternate attach for test injection (in-memory event
  stream); production callers SHOULD use `start!`.

`deps` MUST be an injected map carrying at minimum the event-stream client, an emit function, a
clock (for suppression-window expiry), and the configured suppression window.

### 3.3 Recursion prevention

The correlator MUST filter `:supervisory/automation-edge-upserted` events out of its own input
stream. Failure to filter would cause the snapshot emission to be re-observed as a downstream
event, leading to either re-classification attempts (harmless but log-noisy) or unbounded
emission loops (catastrophic) depending on implementation.

Filtering MUST happen at the subscription level — implementations MAY use a subscription filter
predicate or an explicit skip in the dispatch handler, but the filter MUST be unit-tested.

### 3.4 Trigger classification (pure)

`triggers/classify-trigger` is a pure function `event → RoutingTriggerKind | nil`. The
correlator MUST call this function on every input event and act only when it returns a non-nil
value. Inputs:

In the descriptions below, "Affected PRs" and "Affected agents" name the **fields** the
correlator extracts from the trigger payload; the resulting `:edge/affected-pr-ids` is a vector
of `[<pr/repo-value> <pr/number-value>]` tuples per §2, and `:edge/affected-agent-session-ids`
is a vector of the extracted session UUIDs.

- `:pr/merged` → `:pr-merged`. Affected PRs: derived from `:pr/repo` + `:pr/number` (one
  tuple). Affected agents: `[]` (PR merge does not name an agent in payload v1).
- `:pr-monitor/review-comments-arrived` → `:review-comments-arrived`. Affected PRs: derived
  from `:pr/repo` + `:pr/number` (one tuple). Affected agents:
  `[<:comments/agent-session-id>]` when present.
- `:pr-monitor/ci-failed` → `:ci-failed`. Affected PRs: derived from `:pr/repo` +
  `:pr/number` (one tuple). Affected agents: `[]`.
- `:standards-review/posted` → `:standards-review-arrived`. Affected PRs: derived from
  `:pr/repo` + `:pr/number` (one tuple). Affected agents:
  `[<:affected/workflow-run-id>]` mapped through the workflow → agent index when available;
  `[]` otherwise.
- `:workflow/completed` → `:workflow-completed`. Affected entities derived via the workflow's
  own PR ownership record. NOTE: a `:workflow/completed` event also acts as the **handler
  signal** for a prior trigger (status transition `:observed → :handled`); the correlator MUST
  handle both cases (open a new edge AND transition any pending edge).
- `:gate/passed`, `:gate/failed` → `:gate-fired`. Affected PRs / agents: derived from
  `:workflow/id`.

When the trigger payload does not carry enough information to populate `:edge/affected-pr-ids` or
`:edge/affected-agent-session-ids`, the correlator MUST emit the edge with empty vectors and
log a warn. Consumers (per N5-δ3 §6, AA-2 projection) MUST handle empty vectors gracefully.

### 3.5 Handler-workflow correlation

A handler workflow is "correlated" to a trigger when one of the following holds:

1. **Preferred — explicit.** The workflow's `:workflow/started` event payload carries a
   `:routing/trigger-event-id` field whose value equals an open edge's
   `:edge/trigger-event-id`. Implementations MUST treat this path as authoritative.
2. **Fallback — heuristic.** The workflow starts within a configurable correlation window
   (default 60 s) AND the workflow's `:workflow/spec` matches a registered handler-spec for
   the open edge's `:edge/trigger-kind`. Implementations MUST log a warn whenever the fallback
   path fires (so producers can be audited and migrated to explicit ids).

Handlers that fire on routing decisions (resume-dispatcher, comment-response-agent,
standards-reviewer, pr-monitor) MUST emit `:routing/trigger-event-id` in their handler-workflow
`:workflow/started` payload. This is a normative envelope addition (per §4.3) — the
implementation work is a one-line edit per producer.

When a `:workflow/completed` / `:workflow/failed` event lands and the workflow has no observed
correlation (neither explicit nor heuristic), the correlator MUST NOT transition any edge — it
treats the workflow as an unrelated completion. The pending-edge will time out into
`:needs-operator` per the suppression window.

### 3.6 Pending-edge index — bounded + cross-restart

The correlator MUST maintain an in-memory map of `:edge/trigger-event-id → edge-state`. The map
MUST be bounded — entries MUST be evicted when one of:

1. The edge transitions to a terminal status (`:handled`, `:failed`, `:suppressed`).
2. The suppression window elapses since `:edge/occurred-at`. On expiry, the correlator MUST
   transition the edge to `:needs-operator` per §2.4 before evicting.

Cross-restart: on `start!`, the correlator MUST replay the event-stream prefix and reconstruct
the pending-edge index from the events. Per §2.3 idempotency, replay MUST produce the same
`:edge/id` values; consumers (Rust core) dedup via `:edge/id`. The correlator MUST re-emit
`:supervisory/automation-edge-upserted` events for every edge it reconstructs during replay.

### 3.7 Per-trigger handler-spec registry

The correlator MUST consult a per-trigger-kind handler-spec registry to recognize the heuristic
fallback path (§3.5 case 2). The registry SHOULD be sourced from the same component that
configures the N13 pipeline's routing table. Implementations MAY treat the registry as
optional in v1 — when absent, the heuristic fallback is disabled and only explicit
`:routing/trigger-event-id` correlation fires.

## 4. Event-stream additions

All event additions follow the N3 §2.1 envelope.

### 4.1 `:supervisory/automation-edge-upserted`

```clojure
{:event/type           :supervisory/automation-edge-upserted
 :event/id             #uuid
 :event/timestamp      #inst
 :event/version        "1.0.0"
 :event/sequence-number long
 :message              string
 :supervisory/entity   {… §2 AutomationEdge shape …}}
```

The producer MUST be `components/automation-edge-correlator` and ONLY that component. Per
N5-δ1 invariant 6 (extended), no other component MAY emit
`:supervisory/automation-edge-upserted`.

### 4.2 New trigger event types

Three trigger event types are declared first-class supervisory events. Their schemas MUST be
added to `event-stream/schema.clj` and their emission sites added to the existing components
named below.

All three new trigger event types and the envelope addition in §4.3 are presented as malli
`[:map ...]` schemas, consistent with the §2 entity shape convention. Each schema layers on
the N3 §2.1 envelope (which contributes `:event/type`, `:event/id`, `:event/timestamp`,
`:event/version`, `:event/sequence-number`, `:message`); only the event-specific payload
fields are repeated here.

#### 4.2.1 `:pr-monitor/review-comments-arrived`

```clojure
[:map
 ;; N3 §2.1 envelope contributes :event/id, :event/timestamp, :event/version,
 ;; :event/sequence-number, :message
 [:event/type                 [:= :pr-monitor/review-comments-arrived]]
 [:pr/repo                    string?]
 [:pr/number                  :common/non-neg-int]
 [:comments/count             :common/non-neg-int]
 [:comments/agent-session-id  {:optional true} [:maybe :id/uuid]]]
```

Emitted by `components/pr-monitor` when the GitHub webhook (or polling fallback) reports new
review comments on a PR Miniforge owns.

#### 4.2.2 `:pr-monitor/ci-failed`

```clojure
[:map
 [:event/type     [:= :pr-monitor/ci-failed]]
 [:pr/repo        string?]
 [:pr/number      :common/non-neg-int]
 [:ci/check-name  string?]
 [:ci/conclusion  keyword?]]   ; known values: :failure, :timed-out, :cancelled
```

Emitted by `components/pr-monitor` when a CI status transitions to a non-success terminal
state on a PR Miniforge owns. `:ci/conclusion` is an open keyword — known values listed in the
comment; consumers MUST tolerate additional values for forward compatibility.

#### 4.2.3 `:standards-review/posted`

```clojure
[:map
 [:event/type                [:= :standards-review/posted]]
 [:pr/repo                   string?]
 [:pr/number                 :common/non-neg-int]
 [:affected/workflow-run-id  {:optional true} [:maybe :id/uuid]]
 [:review/severity           keyword?]]   ; known values: :advisory, :blocking
```

Emitted by `components/standards-reviewer` when a review comment lands on a PR.
`:review/severity` is an open keyword — known values listed in the comment.

### 4.3 `:routing/trigger-event-id` envelope addition

Handler workflows fired by N13 routing components MUST attach `:routing/trigger-event-id` to
their `:workflow/started` event payload. The schema for the relevant fields:

```clojure
[:map
 [:event/type                [:= :workflow/started]]
 [:workflow/id               :id/uuid]
 [:workflow/spec             keyword?]
 [:routing/trigger-event-id  {:optional true} [:maybe :id/uuid]]]
```

Producers that MUST attach the field: `components/pr-lifecycle/resume-dispatcher`,
`components/pr-lifecycle/comment-response-agent`, `components/pr-lifecycle/standards-reviewer`,
`components/pr-monitor` (for any auto-triggered fix-loop workflows). The field is `:optional`
in schema for backward compatibility (workflows started outside routing — e.g. operator-
initiated runs — MUST NOT carry it); the correlator's heuristic fallback (§3.5 case 2) covers
the absence.

## 5. TUI / UX rendering

Per the RFC, **TUI rendering is deferred to a follow-on delta** when the native Mac app
(operator console) and Rust TUI projections require it. The Rust core consumer
(`SupervisoryState.automation_edges`) already projects `AutomationEdge` entities into
in-memory state per the AA-2 contract; visual rendering MAY proceed independently in the
Rust / Swift surfaces without further amendment here.

When TUI rendering is added, it MUST surface:

- Edges in `:needs-operator` and `:failed` status as attention items (link into the existing
  attention queue per N5-δ1 §5).
- Edges in `:observed` status as a transient indicator on the affected PR / workflow row.
- Edges in `:handled` / `:suppressed` status in a collapsible audit panel showing the routing
  trail for the run.

## 6. Configuration

Implementations MUST support the following configuration keys (sourced from the standard
miniforge config layer):

- **`:correlator/suppression-window-ms`** — default 300_000 (5 minutes). Time after
  `:edge/occurred-at` before an `:observed` edge with no correlated handler transitions to
  `:needs-operator`.
- **`:correlator/correlation-window-ms`** — default 60_000 (60 seconds). Maximum time after
  trigger for the heuristic fallback (§3.5 case 2) to correlate an explicit-id-less workflow
  to an open edge.
- **`:correlator/heuristic-fallback?`** — default `true`. When `false`, only explicit
  `:routing/trigger-event-id` correlation fires (per §3.7); the heuristic path is disabled.

The suppression window MAY be made per-trigger-kind in a follow-on; v1 is globally tunable
only.

## 7. Rust consumer contract

The `supervisory-entities` crate (`miniforge-control/contracts/crates/supervisory-entities`)
ALREADY carries the consumer half of this contract (AA-2 PR #66): the `AutomationEdge` struct,
the `RoutingTriggerKind` enum, the `SupervisoryState.automation_edges: HashMap<EdgeId,
AutomationEdge>` collection, the `apply_automation_edge` handler in `StateManager`, and the
FFI subscribe-payload inclusion.

The consumer side requires no schema changes from this delta. Wire round-trip MUST hold: the
serde-derived Rust struct MUST round-trip a Clojure-emitted payload byte-for-byte for the
required field set in §2 (consumer-side schema is open per §1.3 / N5-δ3 open-map rule).

The Rust `RoutingTriggerKind` enum value set MUST match §2.1 exactly. Adding a new trigger
kind in this Clojure spec without a matching Rust enum variant MUST be treated as a contract
break; both sides bump in the same change.

## 8. Backwards compatibility and rollout

1. **Additive only.** No existing entity shape changes. No existing event shape changes. The
   `:routing/trigger-event-id` envelope addition is `:optional` and absent on pre-delta
   workflow starts.
2. **No historical backfill.** Edges materialize forward from deployment. The native app /
   TUI MUST tolerate empty `automation_edges` collections from pre-delta event-stream
   prefixes by showing the empty-state placeholder.
3. **Producer-first rollout permitted.** The correlator MAY ship before the
   `:routing/trigger-event-id` field additions land in each handler — the heuristic fallback
   (§3.5 case 2) covers the gap, with warn logs flagging each fallback occurrence so the
   operator can audit migration progress.
4. **Per-trigger-event rollout permitted.** The three new trigger event types (§4.2) MAY
   ship independently; the correlator handles unknown trigger event types gracefully (no
   classification → no edge → no emission).

## 9. Acceptance criteria

The delta is satisfied when all boxes are checked.

### 9.1 Producer + entity

- [ ] `components/automation-edge-correlator` exists with the structure in §3.1
- [ ] `triggers/classify-trigger` is a pure function with unit tests covering every trigger
      event type in §3.4 (positive cases) and at least one non-trigger event (returns `nil`)
- [ ] `correlator/transition` is a pure function with unit tests covering every transition in
      the §2.4 state machine (`:observed → :handled`, `:observed → :failed`, `:observed →
      :needs-operator`, terminal-state → `:suppressed`)
- [ ] `:edge/id` is a deterministic UUIDv5 derived from `:edge/trigger-event-id` alone, with a
      unit test asserting equality across two independent constructions
- [ ] The component subscribes to the event stream and emits
      `:supervisory/automation-edge-upserted` events on trigger observation
- [ ] The component filters its own emissions out of the input stream (unit test asserts no
      re-classification of `:supervisory/automation-edge-upserted` events)
- [ ] The pending-edge index is bounded; entries evict on terminal status or suppression-window
      expiry (unit test asserts both eviction paths)
- [ ] Cross-restart replay reconstructs the same edges with identical `:edge/id` values
      (integration test against an in-memory event stream)
- [ ] `bb poly:check` clean

### 9.2 Event stream

- [ ] `:supervisory/automation-edge-upserted` is declared in `event-stream/schema.clj` with the
      §4.1 shape
- [ ] `:pr-monitor/review-comments-arrived`, `:pr-monitor/ci-failed`,
      `:standards-review/posted` are declared in `event-stream/schema.clj` with the §4.2
      shapes and emission sites exist in the named producers
- [ ] `:routing/trigger-event-id` is added to the `:workflow/started` schema as an optional
      field and the four named handler producers attach it on their fired workflows

### 9.3 Consumer integration (Rust)

- [ ] Rust `RoutingTriggerKind` enum value set matches §2.1 exactly (already true post-AA-2;
      this becomes a regression guard)
- [ ] A live dogfood run populates `SupervisoryState.automation_edges` with at least one edge
      per trigger kind the run exercises
- [ ] The FFI subscribe payload carries the populated `automation_edges` map (already true
      post-AA-2; regression guard)

### 9.4 Invariants

- [ ] Each trigger event produces exactly one edge over its lifetime — verified by an
      integration test that emits a trigger, then a correlated `:workflow/completed`, and
      asserts the resulting projection has exactly one entry keyed by the deterministic
      `:edge/id` with `:edge/status :handled`
- [ ] Replay of an event-stream prefix produces no duplicate edges and no orphan edges
- [ ] No component other than `automation-edge-correlator` emits
      `:supervisory/automation-edge-upserted` (grep-level invariant; CI-checkable)

## 10. Open questions

1. **Per-kind suppression windows.** Some long-running handlers (e.g. a standards-reviewer
   that runs a fix-loop to convergence) may exceed the 5-minute default. v1 is globally
   tunable; per-kind tuning is queued as a follow-on if telemetry shows the global default is
   wrong for a specific trigger kind.
2. **Edge garbage collection.** Terminal-status edges stay in the entity table indefinitely.
   v1 does not prune; long-term storage is handled by the Rust core's SQLite event log per
   the AA-9 retention policy. A prune trigger for archival-only edges is a follow-on.
3. **Multi-edge handler workflows.** A single `:workflow/completed` MAY legitimately handle
   multiple triggers (e.g. a fix workflow that addresses both a CI failure AND a standards-
   review comment). v1 emits separate edges keyed on trigger-event-id; the workflow appears
   as `:edge/handled-by-workflow-run-id` on each. Consumers MUST tolerate this multi-edge
   case.
