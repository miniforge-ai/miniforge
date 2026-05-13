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
  remains as it was after BD-1; it now carries an additional
  `:workflow-run-spec/spec-id` pointer at the long-lived Spec.
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
   - :archived  — operator-archived; suppressed from default UI surfaces"
  #{:draft :active :completed :archived})

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

   Open map: additional keys pass through validation."
  [:map {:registry registry}
   [:spec/id           :spec/id]
   [:spec/title        [:string {:min 1}]]
   [:spec/status       :spec/status]
   [:spec/created-at   :common/timestamp]
   [:spec/updated-at   :common/timestamp]
   ;; Optional metadata (open — see N5-delta-3 §5.1):
   [:spec/description     {:optional true} :string]
   [:spec/intent          {:optional true} :string]
   [:spec/repo-url        {:optional true} :string]
   [:spec/tags            {:optional true} [:vector :string]]
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

Plus the corresponding diff-and-emit pair following the same
pattern as the existing `workflow-*`, `agent-*`, `pr-*`,
`policy-*`, `attention-*` emitter pairs.

## Accumulator additions

`components/supervisory-state/src/ai/miniforge/supervisory_state/accumulator.clj`
gains:

1. **`extract-spec-identity`** helper, mirroring the existing
   `extract-spec-identity` used for `WorkflowRunSpec` (BD-1).
   Distinct function name (`extract-long-lived-spec` or similar)
   to disambiguate — they project from different event shapes.
2. **`upsert-spec`** handler, invoked when:
   - A `:workflow/started` event arrives carrying a fresh
     spec-id not yet in the `:specs` table → create.
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

## Migration / back-compat

- **Existing WorkflowRuns without `spec-id`:** valid. Consumer
  projects to Specless bucket. No data migration required.
- **Pre-existing `~/.miniforge/events/` event files:** consumed
  by `event-client` on replay; new `:supervisory/spec-upserted`
  events arrive in chronological order alongside existing
  `:supervisory/workflow-upserted` events. No retroactive
  emission of Spec snapshots for historical WorkflowRuns — they
  remain Specless until the operator classifies them via the
  Rust-core path.
- **Schema deserialization:** the new `:specs` table key is
  appended; older snapshots without it deserialize to
  `{:specs {} ...}` via malli's open-map handling.

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
