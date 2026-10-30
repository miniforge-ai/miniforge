<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow): a cancel stops a multi-parent task's merge and its resolution agent

## Overview

A cancel now reaches a DAG task while its parent branches are being merged. The merge, and the
merge-resolution agent it may run, are interrupted, and the task ends as stopped instead of as a
merge failure. Stacked on the PR that makes a cancel interrupt the running phase step.

## Motivation

1. A multi-parent task merges its parents before its sub-workflow starts, so before the stop hook
   that interrupts the sub-workflow is installed. A cancel during the merge, or during the
   resolution agent's LLM call, waited for both to finish.
2. The merge code turns an interrupt into an ordinary merge failure, so even an interrupted merge
   was reported as `:anomalies/dag-multi-parent-merge-failed`.
3. An agent error response can swallow the interrupt; the resolution loop then calls the agent
   again within its budget.
4. `dag-merge-git/run-git` used `clojure.java.shell/sh`, which does not end git when the waiting
   thread is interrupted, and turned the interruption into exit -1: git (or a merge driver) kept
   running, and the merge read it as a cache miss and carried on.

## Changes in Detail

1. `dag-sub-workflow/merge-parents`: runs `merge-parent-branches!` in its own interrupting scope
   (`stop-signal/interrupting-on-stop`). Any result under a stopped run, or an interruption that
   escapes, becomes the task's stop.
2. `dag-merge-anomaly/merge-stopped-anomaly`: `:anomalies/dag-merge-stopped`, with the stopped
   status message.
3. `dag-merge-attempt/derive-resolution-overrides` wraps the resolution `:agent-edit-fn` so it does
   nothing once the run's control state is stopped; the loop then ends without another LLM call.
4. `dag-merge-git/run-git` waits on git itself. On an interrupt it kills git and every process git
   started, waits for them, and rethrows, so the merge stages stop. `attempt-merge-with-cache!`
   removes its temporary worktree before the interruption propagates.

## Testing Plan

In `components/workflow/test/ai/miniforge/workflow/stop_signal_test.clj`, each failing on the code
before its fix:

1. `a-cancel-during-a-parent-merge-stops-the-task-test`: a stub merge that swallows the interrupt,
   as the real one does, ends as `:anomalies/dag-merge-stopped` and leaves no interrupt pending.
2. `cancel-at-callback-cooldown-and-resolution-boundaries-test`: the resolution agent is not called
   after the stop.
3. `an-interrupted-git-call-ends-git-and-its-children-test`: git running a child `sleep` is
   interrupted; the call throws and neither process is left. On the previous code both stayed.
4. A merge that finished after the cancel also ends as the task's stop.

Ran the stop-signal, merge-parent integration, merge-resolution and DAG operator-control tests (49).

## Deployment Plan

No configuration or data migration.

## Notes

The resolution loop's own git runner (`merge-resolution`) still turns an interrupt into a failed
command; with the agent guarded it ends within its budget without an LLM call. Changing it needs the
whole-file stratum autofix of `merge_resolution.clj`, so it follows separately.

## Related Issues/PRs

Stacked on #2277 (a cancel interrupts the running phase and its agent CLI); split out to keep that
PR under the size limit.

## Checklist

- [x] Regression tests fail on the previous code
- [x] Independent review and pinned-standards check
- [ ] Copilot threads answered and resolved, CI green, no conflicts
- [ ] Merged
