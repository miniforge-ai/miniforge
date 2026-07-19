<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Design RFC — Supervisory `Spec` Entity

- **Status:** Draft
- **Date:** 2026-05-12
- **Related:** miniforge-control `N5-delta-3-bare-agent-supervision`
  §3 (Spec-first hierarchy) and §6 (`:supervisory/spec-upserted`);
  BD-1 (`WorkflowRunSpec` run-owned spec identity, landed as
  miniforge#793)
- **Consumer:** `miniforge-control` Rust core, which projects this
  entity into its top-level Spec-keyed UI surface

## Why

`miniforge-control`'s N5-delta-3 §3 mandates a **Spec-first
hierarchy** — Spec → MiniforgeRun → WorkflowRun → AgentSession —
with `Spec` as the top-level supervisory entity that anchors every
running workflow, agent, and PR the operator is reasoning about.

The miniforge runtime already has:

- `WorkflowRunSpec` — a snapshot of spec metadata attached to a
  `WorkflowRun` at start time (BD-1, miniforge#793). One per
  WorkflowRun.
- `WorkflowRun` — a concrete execution.
- No notion of a long-lived `Spec` entity that owns N WorkflowRuns
  over time.

The gap: the operator's mental unit is "the spec" ("how is N13
going?"). Without a first-class `Spec`, every consumer of
supervisory state — Rust core, TUI, future native app — has to
reconstruct spec identity by walking `WorkflowRunSpec` snapshots
and de-duplicating by title or some other heuristic. That's both
fragile and a waste of effort that should happen exactly once,
producer-side.

This RFC adds `Spec` as a first-class supervisory entity, emitted
via `:supervisory/spec-upserted` from the existing
`supervisory-state` component.

## What this RFC does not do

- **Does not introduce `MiniforgeRun`** as a new tier between Spec
  and WorkflowRun. N5-delta-3 §3.1 reserves that hierarchy level
  but acknowledges (§5.3, "synthetic Spec reconciliation") that
  the consumer can operate with Spec as a direct parent of
  WorkflowRun for v1. Adding `MiniforgeRun` is a follow-on.
- **Does not change `WorkflowRunSpec`.** The run-owned snapshot
  (`:workflow-run/spec`) remains as BD-1 / miniforge#793 landed
  it. The long-lived Spec foreign key lives on the WorkflowRun
  itself as a new optional `:workflow-run/spec-id` field — see
  below — not inside the snapshot.
- **Does not formalize spec status transitions or workflow
  authoring tooling.** Spec creation is left to whatever currently
  produces `:workflow/spec` payloads (the planner, the CLI, etc.).
  This RFC adds the supervisory-state recording, not the upstream
  authoring surface.

## Schema additions

### `Spec` entity

Add to `components/supervisory-state/src/ai/miniforge/supervisory_state/schema.clj`:

```clojure
(def spec-statuses
  "Lifecycle states a Spec passes through.

   - :draft     — created, no MiniforgeRun yet
   - :active    — at least one MiniforgeRun in flight (or recently completed)
   - :completed — operator-marked done; no future MiniforgeRuns expected
   - :archived  — operator-archived; suppressed from default UI surfaces

   Vector (not set) for deterministic enum ordering in malli printed
   schemas / error messages, matching the convention used for
   `workflow-run-statuses`, `pr-statuses`, etc. throughout `schema.clj`."
  [:draft :active :completed :archived])

;; ... extend the registry:

;; Spec (N5-delta-3 §3.1, §5.1)
:spec/id                  :id/uuid
:spec/status              (into [:enum] spec-statuses)
```

```clojure
(def Spec
  "A long-lived supervisory entity representing the operator's
   top-level unit of work. One Spec owns N MiniforgeRuns (each of
   which owns N WorkflowRuns) over its lifetime.

   Open map: additional keys pass through validation. Field types
   chosen to be compatible with existing spec payload shapes
   (`SpecIntent` in `components/spec-parser/.../schema.clj` and
   the run-owned `:workflow-run/spec` snapshot in this component's
   `schema.clj`)."
  [:map {:registry registry}
   [:spec/id           :spec/id]
   [:spec/title        [:string {:min 1}]]
   [:spec/status       :spec/status]
   [:spec/created-at   :common/timestamp]
   [:spec/updated-at   :common/timestamp]
   ;; Optional metadata (open — see N5-delta-3 §5.1):
   [:spec/description     {:optional true} :string]
   ;; `:spec/intent` is a structured map per `SpecIntent` (`:type`
   ;; + `:scope`); kept as open `map?` here to match the existing
   ;; `:workflow-run/spec` snapshot rather than re-validating against
   ;; the producer-side schema.
   [:spec/intent          {:optional true} map?]
   [:spec/repo-url        {:optional true} :string]
   ;; Tags may be strings OR keywords (matches existing tag
   ;; conventions elsewhere in the codebase).
   [:spec/tags            {:optional true} [:vector [:or :string :keyword]]]
   ;; Origin discriminator — `:miniforge` for specs known to this
   ;; runtime; reserved for `:local-synthetic` when a Rust-core
   ;; consumer creates a Spec ahead of upstream knowing about it
   ;; (N5-delta-3 §5.3).
   [:spec/origin          {:optional true} keyword?]])
```

### `WorkflowRun` extension

Add one optional field to the existing `WorkflowRun` schema
(non-breaking — `default`-tolerant on deserialization):

```clojure
[:workflow-run/spec-id  {:optional true} :spec/id]
```

When set, this is the foreign key to the `Spec` that this
WorkflowRun belongs to. Older WorkflowRuns without a spec-id
remain valid; consumers project them into the "Specless" bucket
(N5-delta-3 §3.3).

### Supervisory table addition

In `schema.clj`'s top-level table, add the new family:

```clojure
[:specs  [:map-of :id/uuid Spec]]
```

(Mirroring the existing `[:workflows [:map-of :id/uuid WorkflowRun]]`
pattern at line 471.)

## Emitter additions

In `components/supervisory-state/src/ai/miniforge/supervisory_state/emitter.clj`:

```clojure
(defn spec-upserted
  [stream spec-entity]
  (-> (es/create-envelope stream
                          :supervisory/spec-upserted
                          (:spec/id spec-entity)
                          (str "Spec " (:spec/title spec-entity) " upserted"))
      (assoc :supervisory/entity spec-entity)))
```

Then extend the existing `diff-and-emit!` function (single
top-level fn in `emitter.clj`) with one additional `emit-diff!`
call for the new `:specs` table:

```clojure
(defn diff-and-emit!
  [stream old-table new-table]
  (let [...]
    (emit-diff! stream spec-upserted     (:specs        old-table) (:specs        new-table))  ;; NEW
    (emit-diff! stream workflow-upserted (:workflows    old-table) (:workflows    new-table))
    ;; ... existing emit-diff! calls unchanged ...
    ))
```

No new per-entity diff function is needed — the existing
`emit-diff!` helper is generic over its constructor argument.

## Accumulator additions

`components/supervisory-state/src/ai/miniforge/supervisory_state/accumulator.clj`
gains:

1. **`extract-long-lived-spec-identity`** — new private helper,
   distinct from the existing `extract-spec-identity` (BD-1, line
   146) which projects the per-run snapshot. The two extract from
   different event shapes: BD-1's helper builds the
   `:workflow-run/spec` snapshot; this RFC's helper extracts the
   long-lived `Spec` entity fields (`:spec/id`, `:spec/title`,
   `:spec/intent`, `:spec/repo-url`, `:spec/tags`, `:spec/origin`).
2. **`upsert-spec`** handler, invoked when:
   - A `:workflow/started` event arrives carrying a fresh
     spec-id not yet in the `:specs` table → create with
     status `:active`.
   - A `:spec/updated` or `:spec/archived` event arrives → mutate.
3. **`link-workflow-to-spec`** — on `:workflow/started`, the
   accumulator records `:workflow-run/spec-id` on the
   newly-created WorkflowRun (which already gets its
   `:workflow-run/spec` snapshot from BD-1; this RFC adds the
   foreign-key link to the long-lived entity).

## Emission triggers

`:supervisory/spec-upserted` is emitted by the accumulator under
the following conditions:

| Condition | Producer event | Spec status |
|---|---|---|
| Spec first observed (first WorkflowRun cites it) | `:workflow/started` with `:workflow/spec` payload | `:active` |
| Spec metadata updated externally | `:spec/updated` (new event type, optional) | unchanged |
| Operator marks complete | `:spec/completed` | `:completed` |
| Operator archives | `:spec/archived` | `:archived` |

The first row is the most important: the existing
`:workflow/started` event becomes the implicit trigger for Spec
materialization. No new producer-side wiring required for the v1
read path.

`:spec/updated`, `:spec/completed`, `:spec/archived` are new
upstream events — they may be added by the upstream authoring
tooling or by the Rust-core consumer's
`:agent/classify-spec` / `:spec/archive` interventions
(N5-delta-3 §6.3) flowing back through the control-plane. Adding
them is out of scope for this RFC; the accumulator should be
ready to handle them when they arrive.

## Data-model semantics

Miniforge is unreleased; there is no installed base to be
backward-compatible *with*. The discipline is "skip all backward-
compat shims and transition periods" (operator policy). This
section therefore documents the **v1 data-model semantics**, not a
migration story.

- **Specless workflows are a first-class state, not a carve-out.**
  `:workflow-run/spec-id` is genuinely optional because some
  `:workflow/started` events don't carry a `:workflow/spec`
  payload at all (exploratory runs, runs whose spec identity the
  operator hasn't classified yet). Such runs project into the
  Specless bucket per N5-delta-3 §3.3. The optional schema marker
  reflects this data-model truth — it is NOT a kindness to
  pre-existing data shapes.
- **Event-stream ordering.** New `:supervisory/spec-upserted`
  events interleave with existing `:supervisory/workflow-upserted`
  events in chronological order on the same event-client channel.
  Producers emit the spec snapshot the first time a workflow
  cites a fresh spec identity; consumers see it before the
  workflow upsert that links to it.
- **In-memory supervisory state.** `supervisory-state/core` is an
  in-memory view cache with no across-restart persistence. The new
  `:specs` table key joins the other top-level table keys via the
  accumulator's `empty-table` initialization — no separate
  snapshot format to think about.

## Sequencing (the burndown)

| Step | Status | Deliverable |
|---|---|---|
| N14-1 | ⬜ next | Schema additions: `Spec`, `:spec/status`, `:spec/id` in registry; `:workflow-run/spec-id` extension; `:specs` table family. Unit-test schema validation. |
| N14-2 | ⬜ | Emitter additions: `spec-upserted` + `diff-and-emit-specs`. Unit-test envelope shape. |
| N14-3 | ⬜ | Accumulator: `upsert-spec` on `:workflow/started`, `link-workflow-to-spec`. Property-test that Spec entity appears exactly once per unique spec-id across N WorkflowRuns. |
| N14-4 | ⬜ | Interface re-exports + integration test. Wire into existing supervisory event flow. |
| N14-5 | ⬜ | `:spec/updated`, `:spec/completed`, `:spec/archived` event handlers in accumulator (no producer-side authoring tooling — just ready to receive). |

Steps N14-1 → N14-4 are required for `miniforge-control`'s AA-2
projection to have real `Spec` events to consume. N14-5 is the
hook for the Rust-core consumer's `:spec/archive` intervention
to flow back through the control-plane round-trip.

Each step is a separate PR; total expected merge surface ~4-5
PRs. Estimated wall-clock with a single implementation agent:
4-6 hours.

## Acceptance for the workstream

- New `:supervisory/spec-upserted` events appear in
  `~/.miniforge/events/<run-id>/` whenever a `:workflow/started`
  cites a new spec identity.
- The supervisory snapshot table carries `:specs` as a top-level
  key, populated with one `Spec` entity per unique spec the
  current process has seen.
- `WorkflowRun.spec-id` is populated for every WorkflowRun
  started after this lands. Older entries remain `nil`.
- All existing tests pass. New schema and accumulator tests are
  green.
- `bb poly:check` clean.

## Open questions

1. **`:spec/updated` shape.** When does a spec's metadata
   *change* — only via operator classification flowing back
   through MCP, or also via upstream amendments to the spec
   payload? Lean: both, idempotent on `:spec/id`, last-write
   wins on metadata fields.
2. **Spec status transitions.** Operator-driven or accumulator-
   derived? Default: explicit (`:spec/completed`,
   `:spec/archived` events) plus an accumulator-side rule that
   transitions `:draft` → `:active` automatically on the first
   `:workflow/started`.
3. **Cross-process Spec identity.** Two operators (or two
   miniforge processes) creating the "same" spec independently
   would emit two upserts with different `:spec/id` UUIDs. v1:
   accept this; reconciliation is a follow-on. v1 dogfooding is
   single-operator-machine (per N5-delta-3 §11) so it doesn't
   surface in practice.

## References

- miniforge-control `N5-delta-3-bare-agent-supervision.md`
  §3 (Spec-first hierarchy), §5.1 (`Spec` entity definition),
  §6.1 (event types — `:supervisory/spec-upserted`)
- miniforge#793 (BD-1: `WorkflowRunSpec` run-owned spec identity)
- `components/supervisory-state/src/ai/miniforge/supervisory_state/schema.clj`
  (entity schemas)
- `components/supervisory-state/src/ai/miniforge/supervisory_state/emitter.clj`
  (existing `*-upserted` emitters as the pattern to mirror)
- `components/supervisory-state/src/ai/miniforge/supervisory_state/accumulator.clj`
  (existing `extract-spec-identity` BD-1 implementation)
