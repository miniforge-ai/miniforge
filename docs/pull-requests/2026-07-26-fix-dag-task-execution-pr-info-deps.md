<!--
  Title: fix(workflow): read :task/deps not :task/dependencies in PR-info extraction
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow): read :task/deps not :task/dependencies in PR-info extraction

## Overview

`extract-pr-info-from-result` (`components/workflow/src/ai/miniforge/workflow/dag_task_execution.clj`)
built its PR-info `:deps` field from `:task/dependencies` on a DAG
task-def. DAG task-defs never carry that key — `plan->dag-tasks`
normalizes and validates a plan task's raw `:task/dependencies` into
`:task/deps` during plan-to-DAG conversion (`dag_plan.clj`). The field
has therefore always evaluated to an empty set, regardless of a task's
actual dependencies.

## Motivation

Flagged as a pre-existing bug: byte-for-byte identical logic existed in
the original monolithic `dag_orchestrator.clj` before its namespace
split (#1485), so this predates that refactor. Checked downstream
consumers before fixing: `dag_train.clj`'s PR-train assembly
(`create-train-from-dag-result`) computes task topology from the plan
tasks directly via `build-task-deps-map`, not from pr-info's `:deps` —
so the bug has produced no observed behavioral regression to date. It's
still wrong data on a field any future consumer (dashboard, evidence
bundle, PR frontmatter) would reasonably trust.

## Changes in Detail

- `dag_task_execution.clj`: `:deps (:task/deps task-def #{})` replaces
  `:deps (set (map dag-plan/normalize-task-id (:task/dependencies task-def [])))`.
  `:task/deps` is already a normalized set (built by `validate-deps` in
  `dag_plan.clj`'s `plan-task->dag-task`), so no re-normalization is
  needed.
- `dag_orchestrator_test.clj`: added
  `extract-pr-info-from-result-reads-task-deps-test` (asserts `:deps`
  reflects a task-def's actual `:task/deps`) and
  `extract-pr-info-from-result-no-deps-test` (root/no-deps case).
  Pre-commit's `stratum-lint --fix` also reformatted the rest of this
  test file (comment placement, `^{:stratum n}` metadata) as a normal
  side effect of staging it — no test logic changed beyond the two new
  `deftest`s.

## Testing Plan

- `clojure -M:poly test brick:workflow` run clean (0 failures) multiple
  times against this change, both before and after main's PR #1485
  namespace split landed mid-session.
- One integration test (`merge_parent_branches_integration_test.clj`,
  real-git-worktree based) flaked twice during iteration on unrelated
  tests; reproduced identically against the unmodified codebase,
  confirming it's pre-existing flakiness, not a regression from this
  change.
- Pre-commit hook's full smoke + GraalVM compatibility suite passed at
  commit time (331 tests, 0 failures; 8 GraalVM-compat tests, 0
  failures).

## Deployment Plan

Standard merge to `main`; no migration, no config change, no runtime
behavior change outside the corrected `:deps` value.

## Related Issues/PRs

- Depends on / follows: #1485 (namespace split of `dag_orchestrator.clj`
  into `dag_plan.clj`, `dag_sub_workflow.clj`, `dag_task_execution.clj`,
  `dag_merge*.clj`, `dag_finalize.clj`, `dag_rate_limit.clj`) — this fix
  targets the post-split location of the function.

## Checklist

- [x] Root cause traced to `plan->dag-tasks` / `plan-task->dag-task`
      normalizing `:task/dependencies` → `:task/deps`, confirmed by
      reading `dag_plan.clj` directly
- [x] Downstream consumers of pr-info's `:deps` checked (`dag_train.clj`)
      — no other consumer found
- [x] Regression tests added for both the with-deps and no-deps cases
- [x] `clojure -M:poly test brick:workflow` clean
- [x] Pre-existing flaky integration test identified and confirmed
      unrelated (reproduced on unmodified code)
- [x] No `--no-verify`; pre-commit hook ran normally at commit time
