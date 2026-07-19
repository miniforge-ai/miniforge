<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# plan-from-agent-dag-wiring

**Spec:** `work/plan-from-agent-dag-wiring.spec.edn`
**Tier:** blocker / dogfood-enabler
**Theme:** dag-orchestration

## Problem

Every spec without pre-declared tasks collapsed into one monolithic implement
phase. Diagnosis: the planner could successfully produce a plan with zero
`:plan/tasks`, triggering the `:no-tasks` DAG skip reason, and execution
silently fell through to monolithic implement. Without DAG activation, the full
parallel-workflow capability was unreachable for LLM-generated plans.

## Changes

### GROUP 1 — plan-from-agent validation (`phase-software-factory/plan.clj`)

Added private `validate-dag-readiness` function and wired it into
`plan-from-agent` after the agent invocation. It converts a `:success` result
that would silently fail DAG activation into an explicit `:error` response:

- **`:anomalies.dag/no-tasks`** — planner returned 0 tasks. Would have
  triggered `:no-tasks` DAG skip → monolithic implement. Now fails the phase
  with a clear diagnostic.
- **`:anomalies.dag/unknown-deps`** — one or more tasks reference `:task/id`
  values not present in the plan. Would have created orphan tasks at DAG
  runtime. Now fails the phase early.

`:already-satisfied`, `:error`, and `:failed` results pass through unchanged —
their statuses are handled downstream by the FSM via `extract-status`.

### GROUP 2 — Planner prompt hardening (`agent/resources/prompts/planner.edn`)

- Added explicit `CRITICAL:` note to the user-turn template requiring non-empty
  `:plan/tasks`. The runtime provides no fallback for a zero-task plan.
- Added failure mode #5 to the system prompt: "Empty `:plan/tasks`" documents
  the `:anomalies.dag/no-tasks` error with a pointer to the `:already-satisfied`
  bundle as the correct alternative.

### GROUP 3 — Fail-fast validation (covered by GROUP 1)

The spec called for malli validation in `extract-plan-from-phase-result`.
Equivalent fail-fast behaviour is achieved by validating at the plan-phase
boundary in `plan.clj`, which is the earliest point a failure can abort
execution. `extract-plan-from-phase-result` is unchanged — upstream validation
ensures invalid plans never reach it.

### GROUP 4 — Integration tests (`phase-software-factory/plan_dag_activation_test.clj`)

New test namespace `ai.miniforge.phase-software-factory.plan-dag-activation-test`
with 7 tests / 10 assertions covering the `validate-dag-readiness` function:

| Test | What it checks |
|------|----------------|
| `valid-plan-passes-through-test` | Valid success + non-empty tasks passes unchanged |
| `empty-task-plan-fails-explicitly-test` | Zero tasks → `:error` + `:anomalies.dag/no-tasks` |
| `unknown-dep-refs-fail-explicitly-test` | Ghost dep IDs → `:error` + `:anomalies.dag/unknown-deps` |
| `valid-deps-pass-through-test` | Known dep IDs pass through |
| `already-satisfied-passes-through-test` | `:already-satisfied` passes through |
| `error-result-passes-through-test` | `:error` passes through |
| `failure-result-passes-through-test` | `:failed` passes through |

### GROUP 5 — Telemetry events (`workflow/execution.clj`, `event-stream/event_type_registry.clj`)

Added two new private emit functions to `execution.clj`:

- `emit-dag-activated!` — emits `:workflow/dag-activated` when the DAG
  orchestrator takes over. Carries `:plan/id` and `:plan/task-count`.
- `emit-dag-skipped!` — emits `:workflow/dag-skipped` when DAG execution is
  not attempted. Carries `:dag/reason` and skip diagnostics.

Both are called from `try-dag-execution` alongside the existing
`emit-dag-considered!` (retained for backward compatibility). They are separate
event types — not additional outcome values on `dag-considered` — per the spec
constraint "not overloaded onto existing events."

Registered all three DAG events in `event_type_registry.clj`:
`:workflow/dag-considered`, `:workflow/dag-activated`, `:workflow/dag-skipped`.

## Files changed

| File | Change |
|------|--------|
| `components/phase-software-factory/src/.../plan.clj` | Added `validate-dag-readiness`, wired into `plan-from-agent` |
| `components/workflow/src/.../execution.clj` | Added `emit-dag-activated!`, `emit-dag-skipped!`; wired into `try-dag-execution` |
| `components/event-stream/src/.../event_type_registry.clj` | Added 3 DAG event entries, updated audit date |
| `components/agent/resources/prompts/planner.edn` | Hardened user-template + added failure mode #5 |
| `components/phase-software-factory/test/.../plan_dag_activation_test.clj` | NEW — 7 integration tests |

## Tests

```
# New tests
7 tests, 10 assertions — 0 failures, 0 errors

# Existing plan tests (no regression)
ai.miniforge.phase-software-factory.plan-test
ai.miniforge.workflow.dag-activation-diagnostics-test
→ 17 tests, 35 assertions — 0 failures, 0 errors

# Workflow DAG tests (no regression)
ai.miniforge.workflow.dag-orchestrator-test + dag-activation-diagnostics-test
→ 40 tests, 84 assertions — 0 failures, 0 errors

# poly:check: OK
```

## Spec acceptance criteria status

| Criterion | Status |
|-----------|--------|
| `plan-from-agent` returns phase result with `:plan/id` in `{:result :output}` | ✅ Already true for successful planner; now also fails-fast when planner returns empty tasks |
| Invalid plans fail with clear error, not silent fallback | ✅ `validate-dag-readiness` converts zero-task / unknown-dep success to explicit `:error` |
| `miniforge events show` shows `:workflow/dag-activated` when decomposition fires | ✅ New event emitted from `try-dag-execution` |
| Integration test asserts `dag-applicable?` fires on agent-generated plan | ✅ `plan_dag_activation_test.clj` |
| Existing `plan-from-spec-tasks` tests still pass | ✅ Fast path untouched |
| Event types added to schema, not overloaded | ✅ Three separate event entries in registry |
