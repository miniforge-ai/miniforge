<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow): a cancel stops a multi-parent task's merge and its resolution agent

## Overview

A cancel now reaches a DAG task while its parent branches are merged. A run stopped before the
merge does not merge. The resolution agent's LLM calls see the cancel. Once the run is stopped
the agent is not called again, and no resolution is committed. A merge that ends under a stopped
run fails the task with `:anomalies/dag-merge-stopped`, and the run ends cancelled. Nothing is
interrupted, as in #2277.

## Motivation

1. A multi-parent task merges its parents before its sub-workflow starts. Its phases, which bind
   the run's LLM cancel, have not run yet.
2. Under `mf run plan.edn` no phase runs around the DAG either, so the resolution agent's LLM call
   ran without the cancel signal and finished on its own.
3. A cancelled agent call returns an error response. The resolution loop then calls the agent
   again, within its budget.
4. A resolution the cancel cut short could still pass the default verify and be committed. It
   was then cached under the task's merge ref, where a later attempt could reuse it.
5. A merge cut short by the cancel was reported as an ordinary merge failure.

## Changes in Detail

1. `dag-sub-workflow/merge-parents` skips the merge when the run is stopped. Otherwise it runs
   `merge-parent-branches!` inside `llm/call-with-cancel-signal`. A merge that ends under a
   stopped run becomes the task's stop. When the merge itself failed, its anomaly is kept under
   `:merge/failure`.
2. `dag-merge-anomaly/merge-stopped-anomaly`: `:anomalies/dag-merge-stopped`, with the stopped
   status message.
3. `dag-merge-attempt/derive-resolution-overrides` wraps the resolution `:agent-edit-fn` and
   `:verify-fn`. Once the run's control state is stopped, the edit fn does nothing and verify
   fails. The loop then ends within its budget, with no LLM call and no commit.

## Testing Plan

`projects/miniforge/test/ai/miniforge/workflow/dag_merge_cancel_test.clj`:

1. A run stopped before the merge ends the task as `:anomalies/dag-merge-stopped` without merging.
2. A merge that ends after the cancel is the task's stop, and a merge failure is kept. A merge with
   no cancel keeps its result.
3. An LLM call inside the merge sees the run's cancel.
4. The resolution agent is not called after the stop, and verify fails after it.

On #2277 alone 8 of the 9 assertions fail or error. Only the no-cancel case in 2 passes there,
as it should. Locally on macOS the merge-cancel tests pass, 9 assertions. So do the merge-parent
integration, merge-resolution, merge-resolution agent, stop-signal, DAG operator-control, DAG
task-execution and DAG orchestrator tests: 75 tests, 0 failures.

## Deployment Plan

No configuration or data migration.

## Notes

1. Each git command in the merge runs to its end. An earlier version killed git on a cancel. A
   killed git can leave lock files behind, and the temporary worktree's cleanup then raced it.
   A merge's git steps take seconds, so the stop is checked around the merge instead.
2. The resolution loop's own git runner (`merge-resolution`) is unchanged. With the agent guarded,
   the loop ends within its budget without an LLM call.

## Related Issues/PRs

1. miniforge-ai/miniforge#2277: the base of this PR.
2. miniforge-ai/miniforge#2377: the cancel issue.

## Checklist

1. [x] Regression tests fail on the previous code
2. [ ] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
