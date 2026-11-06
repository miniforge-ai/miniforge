<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow): a cancel interrupts the running phase and its agent CLI

## Overview

A cancel now reaches work in flight. It interrupts the running phase step, which kills the agent
CLI it waits on. The run is recorded as stopped at that point, not after the phase finishes on its
own.

## Motivation

A cancel only set `:stopped` in the run's control state, read at phase boundaries and before each
DAG batch. A phase waiting on the agent CLI kept running, and spending, until it finished.

## Changes in Detail

1. `workflow/stop-signal` (new): `on-stop!` fires once when the control state becomes stopped.
   `interrupting-on-stop` runs a function and interrupts its thread on a stop. The callback
   interrupts only under a monitor while the scope is armed. Teardown disarms first, so it cannot
   be interrupted after the fact. Only the interrupt the scope caused is cleared on exit. One
   pending on entry is kept, and so is one arriving after `coordinating` cleared the scope's own.
   `coordinating` disarms the scope for work that waits on tasks which stop on their own.
   `stop-caused?` names the test "the run is stopped and this failure is an interruption".
2. `runner-phase-step/execute-phase` runs only the phase step in that scope (`run-phase-step`).
   Workspace persistence, `env/track-unpersisted` and the retry and health policy run after it,
   outside the scope. An interruption escaping the step under a stopped run leaves the input
   context for the runner to record.
3. `runner-iteration/execute-supervised` keeps main's `llm/call-with-cancel-signal` (#2389)
   around the phase step, so a streaming agent call also sees the cancel directly.
   `execute-single-iteration` re-checks the stop after the phase and records `:dashboard-stop`
   before the checkpoint, unless the phase already completed the run.
4. Boundaries inside a phase:
   1. a boundary callback the stop interrupted refuses the boundary, and the context keeps the
      phase's results. This holds for an InterruptedException and for ClosedByInterrupt from
      channel I/O; any other interrupt propagates;
   2. a DAG batch waits for its tasks in `coordinating` and collects each result;
   3. the DAG pause wait and the short rate-limit wait end on a cancel's interrupt; a cancel that
      ended the rate-limit wait keeps the batch's spend.
5. `enter-implement` runs no curator when the run is stopped after the agent. A cancel during a
   curator that then fails ends the phase as `:run-stopped`; a curator that succeeded keeps its
   work. The stopped result keeps the agent's metrics and cost.

## Follow-ups stacked on this PR

1. #2294: a multi-parent task's merge and its resolution agent stop on a cancel.
2. #2405: the retry backoff and the DAG waits wake on a cancel outside a phase scope
   (`stop-signal/sleep!`). A cancel also wins over a rate-limit failover or pause decision. These
   were Copilot findings on this PR, moved to their own PR to keep this one under the size limit.

## Testing Plan

1. Recovery scenario, end to end:
   `projects/miniforge/test/ai/miniforge/workflow/cancel_reaches_llm_test.clj` runs the real LLM
   client against a stub CLI that starts a long child. After the cancel the call ends and no
   process of the tree is left.
2. `stop_signal_test.clj` covers interrupt and teardown ordering and DAG batch collection. It
   covers phase-step boundaries: persistence uninterrupted, `:dashboard-stop` recorded, a
   completed run left completed. It also covers callbacks (including channel I/O), pause and
   cooldown boundaries, and interrupts from elsewhere being kept.
3. `implement_test.clj`: no curator after a cancel. A curator that fails after a cancel ends the
   phase stopped with the agent's spend; one that succeeded keeps its work.
4. Each test fails on the code before its fix. The workflow runner, DAG, merge, execution-step
   and stop-signal suites, the implement tests and main's `runner-iteration-control-test` pass
   with this PR on top of the splits.

## Deployment Plan

No configuration or data migration.

## Related Issues/PRs

1. miniforge-ai/miniforge#2377: the cancel issue this addresses.
2. #2400 and #2401: the splits of `dag_orchestrator.clj` and `implement.clj` this PR is
   built on, so no commit here needs the stratum-lint warn mode.
3. #2384 and #2389: the LLM-side cancel already on main.
4. The LLM paths kill the agent CLI's process tree on interrupt (#2120, #2217).

## Notes

1. The curator's own collection fallbacks and LLM enrichment do not check the stop. The first edit
   to `curator.clj` triggers a whole-file stratum autofix (about 860 lines), so that change
   follows a separate mechanical PR. Until then a cancel during a curator run can allow one
   enrichment call.
2. A cancel during a DAG task's environment acquisition waits for that acquisition: the
   `git worktree add` runs serialized behind `worktree-lock`. The task's first iteration then
   checks the stop before any phase and releases the environment, so no agent work runs. The
   delay is bounded by the acquisitions queued ahead. Interrupting acquisition itself needs an
   interruptible lock and cleanup of a half-made worktree in dag-executor, and is left out.

## Checklist

1. [x] Regression tests fail on the previous code
2. [x] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
