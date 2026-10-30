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

## Changes in Detail

1. `dag-sub-workflow/merge-parents`: runs `merge-parent-branches!` in its own interrupting scope
   (`stop-signal/interrupting-on-stop`). A merge error under a stopped run, or an interruption that
   escapes, becomes the task's stop.
2. `dag-merge-anomaly/merge-stopped-anomaly`: `:anomalies/dag-merge-stopped`, with the stopped
   status message.
3. `dag-merge-attempt/derive-resolution-overrides` wraps the resolution `:agent-edit-fn` so it does
   nothing once the run's control state is stopped; the loop then ends without another LLM call.

## Testing Plan

In `components/workflow/test/ai/miniforge/workflow/stop_signal_test.clj`, each failing on the code
before its fix:

1. `a-cancel-during-a-parent-merge-stops-the-task-test`: a stub merge that swallows the interrupt,
   as the real one does, ends as `:anomalies/dag-merge-stopped` and leaves no interrupt pending.
2. `cancel-at-callback-cooldown-and-resolution-boundaries-test`: the resolution agent is not called
   after the stop.

Ran the stop-signal, DAG merge and DAG sub-workflow tests.

## Deployment Plan

No configuration or data migration.

## Related Issues/PRs

Stacked on #2277 (a cancel interrupts the running phase and its agent CLI); split out to keep that
PR under the size limit.

## Checklist

- [x] Regression tests fail on the previous code
- [x] Independent review and pinned-standards check
- [ ] Copilot threads answered and resolved, CI green, no conflicts
- [ ] Merged
