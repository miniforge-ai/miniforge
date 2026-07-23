<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Minibench product adapter: orchestration slice

Status: implemented on 2026-07-19

## Result

The Miniforge-owned orchestration adapter projects supervisory state — the
EntityTable materialized from the N2 §2.4 lifecycle event stream — into a
validated `workbench_snapshot/v1`. Minibench remains tenant-agnostic: it
consumes the snapshot and the product registry, never a Miniforge domain
namespace. This is the orchestration counterpart to the ETL slice
(`docs/architecture/minibench-etl-adapter.md`), and it replaces the
hand-written `fixtures/miniforge/snapshot.json` stand-in the workbench
contract has round-tripped so far.

## Resolved-run boundary

The ETL slice flagged that orchestration lacked "a single canonical
resolved-run value … assembled and frozen at the run boundary". This
adapter defines that boundary for workflow runs:

- A run is **resolved** when its lifecycle projection has reached a
  terminal status per N2 §2.2–§2.3: `:completed`, `:failed`, or
  `:cancelled`. The phase machine's own terminal node is `:done` (no
  outgoing transitions in the authoritative transition map); `:failed`
  and `:cancelled` are lifecycle-terminal from any phase.
- **Unresolved (dangling) runs are excluded from snapshots.** `project`
  rejects them at the boundary, and `resolved-runs` never returns them.
  The `miniforge.workflow.runs_resolved` state variable measures how much
  of the table sits at the boundary, so dangling runs stay visible even
  though they are not projectable.
- **Snapshot identity**: `run_id` is the workflow run's UUID;
  `snapshot_id` defaults to `wb-<run_id>`. `generated_at` is supplied by
  the caller and defaults to the run's own `:workflow-run/updated-at` —
  the projection is pure and reads no clock, mirroring the ETL adapter's
  purity discipline.
- **Observed phase history** is not retained on the EntityTable (only
  `:workflow-run/current-phase` is), so it is derived as a pure fold over
  the same `:workflow/phase-started` events the caller already folded
  into the table (`phase-history`), and passed to `project` alongside the
  legal transition map from the workflow component's interface.

The full resolved-run *configuration* inventory (workflow, phase, prompt,
model, runner, and user defaults assembled into one frozen value) remains
future work; until orchestration assembles that value at run start, the
ETL factor-inventory/diff primitive cannot count orchestration factors
defensibly. This slice freezes the *outcome* boundary — which runs are
comparable at all — and the supervisory evidence behind it.

## Orchestration state variables

The product registry is `miniforge-orchestration-state-vars@2026.07.19.1`
and defines six deterministic, LLM-free variables, each evaluable from the
EntityTable plus the observed phase history:

- `miniforge.workflow.machine_authoritative` — observed phase history is a
  path through the single authoritative execution machine and the terminal
  projection is legal (N2 §2.1–§2.3, §3.1).
- `miniforge.workflow.runs_resolved` — fraction of runs at the resolved-run
  boundary (N2 §2.2–§2.3).
- `miniforge.gate.critical_violations_block` — every run-scoped
  PolicyEvaluation carrying a critical violation has a blocking failed
  outcome (N4 §3, §3.3).
- `miniforge.policy.evaluations_recorded` — a run that traversed `:verify`
  has at least one recorded PolicyEvaluation (N4 §3; N2 §2.4).
- `miniforge.attention.items_actioned` — fraction of the run's
  AttentionItems resolved at the boundary (N5-delta-1 §3.1, §5).
- `miniforge.decision.queue_drained` — DecisionCards resolved and
  InterventionRequests settled at the boundary (N5-delta-3 §2.4;
  N5-delta-supervisory-control-plane §3.3).

Evidence refs are honest: they carry real supervisory entity/event ids
(workflow run ids, PolicyEvaluation ids, AttentionItem ids, DecisionCard
ids, InterventionRequest ids) with `source_role` values matching each
variable's `evidence_requirements`. Snapshot metadata stamps evaluator and
policy hash/version plus the resolved-run boundary summary (terminal
status, phase history, dangling-run count).

## Usage

```clojure
(require '[ai.miniforge.supervisory-state.interface :as sup]
         '[ai.miniforge.workflow.interface :as workflow]
         '[ai.miniforge.workbench-orchestration-adapter.interface :as adapter])

(let [table  (sup/apply-events sup/empty-table events)
      run-id (:workflow-run/id (first (adapter/resolved-runs table)))]
  (adapter/project table run-id
                   {:experiment-id "miniforge.orchestration.example"
                    :label "baseline"
                    :transitions workflow/phase-transitions
                    :phase-history (adapter/phase-history events run-id)}))
```

Registry export for minibench comparison:

```bash
bb workbench:orchestration:registry :out '"runs/miniforge-orchestration-registry.json"'
```

The adapter is deliberately not wired into `bases/cli` yet: PR #1426
(the ETL slice) already modifies `bases/cli/main.clj`, the ETL command
file, and the CLI message resources, so CLI exposure is deferred to a
follow-up after that PR merges. The component interface plus the bb task
above are the exposure for now.

## Fixture provenance

The adapter tests never hand-roll EntityTable shapes: fixtures fold
synthetic N2 §2.4 event streams through the real supervisory-state
accumulator (`sup/apply-events`, newly exposed on its interface as a pure
pass-through) and assert on the projected table before projecting. The
transition map under test is the real `workflow/phase-transitions`. A
follow-up in the minibench/workbench-contract repo should replace the
hand-written `fixtures/miniforge/{registry,snapshot}.json` pair with this
adapter's real output.

## Verification

- adapter contract, registry, boundary-exclusion, illegal-transition,
  unblocked-critical, missing-evaluation, and not-applicable tests;
- accumulator-shape assertion test (table produced by the real reducer);
- snapshot and registry validated against the workbench-contract Clojure
  mirror (`ai.miniforge.workbench-contract.schema`), same as the ETL
  adapter;
- Polylith workspace checking and targeted clj-kondo linting.
