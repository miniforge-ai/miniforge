# fix: DAG phantom dependency detection + unreached task reporting

**Branch:** `fix/dag-phantom-deps-unreached-tasks`
**Base:** `refactor/phase-status-and-neutral-result`
**Date:** 2026-03-04

## Problem

Dogfooding `dashboard-production-ready.spec.edn` (run 2) produced a 25-task DAG.
14 tasks completed, 0 failed — but 11 tasks silently never ran. The workflow
reported `:success` with no indication that 44% of tasks were dropped.

### Root Cause

The LLM planner generates task dependencies as UUIDs, but some reference task IDs
that don't exist in the plan (hallucinated/mismatched UUIDs). These phantom deps
pass `ensure-uuid` (they're valid UUID strings) but point to nowhere.

In `compute-ready-tasks`, a task is only ready when **every** dep is in
`completed-ids`. A phantom dep will never appear in `completed-ids`, so the task
is permanently stuck — but it's also never marked as failed (nothing actually
failed), so `propagate-failures` has nothing to propagate from.

The loop exits on `(empty? ready-tasks)` and reports success with 0 failures,
silently losing the stuck tasks.

### Impact

```text
Before fix:
  25-task DAG → 14 completed, 0 failed, 0 reported unreached
  11 tasks silently dropped — no warning, no error, workflow reports :success

After fix:
  Same plan → all 25 tasks complete (phantom deps stripped at plan conversion)
  If tasks ARE unreached for other reasons, :tasks-unreached is reported
```

## Changes

### 1. Phantom dependency filtering in `plan->dag-tasks`

**File:** `dag_orchestrator.clj` (Layer 1)

`plan->dag-tasks` now validates each dependency UUID against the set of actual
task IDs in the plan. Invalid deps are dropped with a WARN log. Tasks that
previously would have been permanently stuck now become unblocked.

### 2. Unreached task detection in `execute-dag-loop`

**File:** `dag_orchestrator.clj` (Layer 2)

When the loop terminates on `(empty? ready-tasks)`, it now computes
`unreached = total - completed - failed`. If positive:

- Logs `:dag/unreached-tasks` with stuck task IDs and their unmet deps
- Adds `:tasks-unreached` to the result map
- Adds `:unreached` to the `:dag/completed` log event

This ensures no task is ever silently dropped.

### 3. Regression tests (6 new tests)

**File:** `dag_resilience_test.clj` (Layers 5)

| Test | What it validates |
|------|-------------------|
| `test-plan->dag-tasks-drops-phantom-deps` | Phantom UUID deps filtered, valid deps preserved |
| `test-plan->dag-tasks-drops-string-phantom-deps` | String-format phantom deps also filtered |
| `test-plan->dag-tasks-all-deps-valid` | Valid deps pass through unchanged |
| `test-phantom-deps-caused-stuck-tasks-before-fix` | End-to-end: phantom dep task now completes (was silently stuck) |
| `test-unreached-tasks-reported-in-result` | Failed deps propagate correctly, `tasks-failed` accounts for transitive failures |
| `test-all-tasks-accounted-for` | `completed + failed + unreached = total` — no silent data loss |

## Files Changed

| File | Type | Description |
|------|------|-------------|
| `components/workflow/src/.../dag_orchestrator.clj` | Modified | Phantom dep filtering + unreached task detection |
| `components/workflow/test/.../dag_resilience_test.clj` | Modified | 6 regression tests |
| `docs/pull-requests/2026-03-04-fix-dag-phantom-deps-unreached-tasks.md` | New | This PR doc |

## Testing

- [x] `bb test` — 297 tests, 1187 assertions, 0 failures
- [x] All 6 new regression tests pass
- [x] All 14 existing resilience + orchestrator tests unaffected
