<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow): a cancel ends the run's waits and is recorded where it lands

## Overview

A cancel now ends the work in flight without interrupting any thread. #2389's LLM cancel already
ends an agent call in flight. This PR adds the rest. The runner records the stop of a phase the
cancel cut short. The implement phase runs no curator after a cancel. The retry backoff and the
DAG's pause and rate-limit waits end on a cancel.

## Motivation

1. A cancel only sets `:stopped` in the run's control state. #2389 made LLM calls read it. A call
   in flight kills the agent's process tree and returns its usage, and a later call ends at once.
2. The phase then returns as an ordinary failure, and the run ended there. The checkpoint held
   that failure, and the stop was never recorded.
3. Waits ignored the cancel. The retry backoff, the DAG pause poll and the DAG rate-limit
   cooldown each ran to the end.
4. After a cancelled agent call, the implement phase still ran the curator, which can call an LLM.
5. A cancel during a rate-limit failover or pause decision lost to that decision. The run ended
   paused, without its spend, and could publish an auto-resume hint.
6. An earlier version of this PR interrupted the phase thread on a cancel. With #2389 on main,
   the interrupt lost the agent's spend, because an interrupted stream rethrows without its
   usage. It could also block event writes, because the commit journal refuses writes on an
   interrupted thread. This version interrupts nothing.

## Changes in Detail

1. `workflow/stop-signal` (new): `stopped?` reads the control state. `sleep!` sleeps in 100 ms
   slices and returns early on a stop.
2. `runner-iteration/execute-single-iteration` checks the stop again after the phase. It records
   `:dashboard-stop` once, before the checkpoint. It skips a run the phase completed, and a stop
   the phase already recorded, as a DAG phase does.
3. `runner-phase-step/execute-phase` takes the control state, and the retry backoff uses `sleep!`.
4. `dag-loop`: the pause poll and the rate-limit wait (`resilience/*sleep!*`) use `sleep!`. A stop
   wins over any rate-limit decision and keeps the batch's spend. The next iteration returns the
   cancelled result. A rate-limit wait that a caller already bound, such as a test's fake clock,
   is left as it is.
5. `dag-rate-limit/handle-rate-limit-in-batch` publishes no `:dag/paused` or `:dag/resume-hint`
   once the run is stopped.
6. `enter-implement` runs no curator after an agent turn the cancel ended. A turn that succeeded
   before the cancel is still curated, so its work is kept. A cancel during a curator that then
   fails ends the phase as `:run-stopped`. The stopped result keeps the agent's metrics and cost.

## Follow-ups stacked on this PR

This PR is the base of #2294. With #2294, a multi-parent task's merge and its resolution agent
stop on a cancel.

## Testing Plan

1. End to end, `projects/miniforge/test/ai/miniforge/workflow/cancel_reaches_llm_test.clj` runs
   the real runner iteration and the LLM client's capture path. The stub CLI starts a long child.
   After the cancel the phase returns and the call reports `:cancelled`. The stop is recorded and
   no process of the tree is left.
2. `stop_signal_test.clj` covers `sleep!` and the stop recorded before the checkpoint. It checks
   that a completed run stays completed. A cancel ends a 20 s retry backoff and a 10 s pause poll.
3. `dag_operator_control_test.clj`: a cancel during a 2-minute rate-limit wait ends the wait and
   keeps the batch's spend. No phase runs around the DAG, as in `mf run plan.edn`. A cancel during
   a 3-hour pause decision ends the run cancelled with its spend. No pause or resume-hint event is
   published.
4. `implement_test.clj`: a cancelled agent turn gets no curator. A turn that succeeded before the
   cancel is curated. A curator that fails after a cancel ends the phase stopped with the agent's
   spend. One that succeeded keeps its work.

Local results on macOS:

1. The workflow `dag-*`, `runner-*`, `execution-*`, `gate-denied-persist` and `stop-signal`
   suites, with the implement and implement-spend tests: 386 of 387 pass.
2. The one failure is `runner_checkpoint_test`'s relative-root test. It fails the same way on
   main in this checkout.
3. The end-to-end test passes.
4. On main's code the new tests give 14 failures and 1 error. The end-to-end test fails there on
   the missing stop record.

## Deployment Plan

No configuration or data migration.

## Related Issues/PRs

1. miniforge-ai/miniforge#2377: the cancel issue this addresses.
2. #2384 and #2389: the LLM-side cancel already on main.
3. #2400 and #2401: the splits of `dag_orchestrator.clj` and `implement.clj` this PR builds on.
4. #2405: the wait fixes, first split out for size and now folded back into this PR.

## Notes

1. Work that is neither an LLM call nor a wait runs to its end after a cancel. Examples are a test
   or build command in verify and a git command. These spend no tokens. The runner records the
   stop when the phase returns.
2. An LLM call on an HTTP backend does not see the cancel, so one in flight runs to its end.
   #2389's cancel covers the CLI capture and stream paths.
3. After a turn that succeeded, the curator runs inside the phase's cancel binding. On a CLI
   backend its LLM enrichment call therefore ends at once after a cancel.
4. The PR monitor's wait in `dag-monitor` does not end on a cancel. It runs up to 100 cycles of
   30 s, and a cycle can still merge a ready PR. `dag_monitor.clj` has layer headings without
   stratum metadata, so its first edit rewrites the whole file. A mechanical stratum PR goes
   first, and the fix follows it.
5. A cancel during a DAG task's environment acquisition waits for that acquisition. The task's
   first iteration then checks the stop before any phase and releases the environment.
6. `handle-rate-limit-in-batch` checks the stop before it publishes its pause events. A cancel
   that lands during those writes still ends the run cancelled, but the events are out. That
   window is the same as a cancel landing just after the run paused.

## Checklist

1. [x] Regression tests fail on the previous code
2. [ ] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
