<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow): a cancel interrupts the running phase and its agent CLI

## Overview

A cancel now reaches work in flight. It interrupts the running phase step, which kills the agent
CLI it waits on, and the run is recorded as stopped at that point instead of after the phase
finishes on its own.

## Motivation

A cancel only set `:stopped` in the run's control state, read at phase boundaries and before each
DAG batch. A phase waiting on the agent CLI kept running, and spending, until it finished.

## Changes in Detail

1. `workflow/stop-signal` (new): `on-stop!` fires once when the control state becomes stopped.
   `interrupting-on-stop` runs a function and interrupts its thread on a stop. The callback
   interrupts only under a monitor while the scope is armed, so teardown, which disarms first,
   cannot be interrupted after the fact. Only the interrupt it caused is cleared on exit: one pending
   on entry is kept, and so is one arriving after `coordinating` cleared the scope's own.
   `coordinating` disarms the scope for work that waits on tasks which stop on their own.
   `stop-caused?` names the test "the run is stopped and this failure is an interruption".
   `sleep!` sleeps unless the run is stopped and wakes on a stop: through the thread's own armed
   scope when there is one, else in a scope of its own, so a wait outside a phase step (the
   retry backoff, `mf run plan.edn`'s DAG loop) wakes too.
2. `runner-phase-step/execute-phase` runs only the phase step in that scope (`run-phase-step`);
   workspace persistence (and main's `env/track-unpersisted` after it) and the retry and health
   policy run after it, outside the scope. The retry backoff uses `stop-signal/sleep!`, so a
   cancel before or during it ends it at once. An interruption escaping the step under a stopped
   run leaves the input context for the runner to record.
3. `execute-single-iteration` re-checks the stop after the phase and records `:dashboard-stop`
   before the checkpoint, unless the phase already completed the run.
4. Boundaries inside a phase:
   1. a boundary callback the stop interrupted refuses the boundary (the context keeps the phase's
      results), whether the interruption is an InterruptedException or ClosedByInterrupt from
      channel I/O; any other interrupt propagates;
   2. a DAG batch waits for its tasks in `coordinating` and collects each result;
   3. the DAG pause wait and the short rate-limit wait use `stop-signal/sleep!` and end on a
      stop, with or without a phase scope around the DAG. A cancel that lands during the
      rate-limit wait, a failover, or a pause decision wins over that decision: the run ends
      cancelled with the batch's spend, and no `:dag/paused` or `:dag/resume-hint` is published
      for it;
   4. a multi-parent task's merge and its resolution agent are covered in #2294, stacked on this
      PR (kept out to stay under the PR size limit).
5. `enter-implement` runs no curator when the run is stopped after the agent. A cancel that lands
   during a curator that then fails ends the phase as `:run-stopped`; a curator that succeeded
   keeps its work. The stopped result keeps the agent's metrics and cost.

## Testing Plan

1. Recovery scenario, end to end: `projects/miniforge/test/ai/miniforge/workflow/cancel_reaches_llm_test.clj`
   runs the real LLM client against a stub CLI that starts a long child; after the cancel the call
   ends and no process of the tree is left.
2. `stop_signal_test.clj`: interrupt and teardown ordering, DAG batch collection, phase-step
   boundaries (persistence uninterrupted, `:dashboard-stop` recorded, a completed run left
   completed), callback (including channel I/O), pause and cooldown boundaries, `sleep!` inside and
   outside a scope, a cancel during the retry backoff (a 20 s backoff ends within 5 s), and
   interrupts from elsewhere kept.
3. `implement_test.clj`: no curator after a cancel; a curator that fails after a cancel ends the
   phase stopped with the agent's spend; one that succeeded keeps its work.
4. `dag_operator_control_test.clj`: a cancel during a 2-minute rate-limit wait with no phase scope
   ends the wait and keeps the batch's spend; a cancel during a 3-hour (pause) decision ends the
   run cancelled with its spend and publishes no pause or resume hint.
5. Each test fails on the code before its fix. Ran the workflow runner, DAG, merge and
   execution-step suites (371 tests) and the implement tests.

## Deployment Plan

No configuration or data migration.

## Related Issues/PRs

The LLM paths kill the agent CLI's process tree on interrupt (#2120, #2217).

## Notes

1. The curator's own collection fallbacks and LLM enrichment do not check the stop: the first edit
   to `curator.clj` triggers a whole-file stratum autofix (about 860 lines), so it follows a
   separate mechanical PR. Until then a cancel during a curator run can allow one enrichment call.
2. `dag_orchestrator.clj` (4 layers) and `implement.clj` (7) were already over SL003 on main;
   commits touching them used `MINIFORGE_STRATUM_BUDGET_MODE=warn`, adding no layer.
3. A cancel during a DAG task's environment acquisition (the `git worktree add` serialized behind
   `worktree-lock`) waits for that acquisition. The task's first iteration then checks the stop
   before any phase and releases the environment, so no agent work runs; the delay is bounded by
   the acquisitions queued ahead. Interrupting acquisition itself needs an interruptible lock and
   cleanup of a half-made worktree in dag-executor, and is left out.

## Checklist

- [x] Regression tests fail on the previous code
- [x] Independent review and pinned-standards check
- [ ] Copilot threads answered and resolved, CI green, no conflicts
- [ ] Merged
