<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Cancel stops the phase in flight, and its agent subprocess

## Overview

`mf workflow cancel` (and the dashboard and TUI stop) sets `:stopped` on the
run's control state. The runner read it only between phases, and the DAG
orchestrator between batches, so a cancelled run kept going until the
current phase finished: the agent CLI, its MCP servers and its Bash
children ran on, possibly for many minutes, editing the worktree and
spending tokens after the operator said stop.

The runner now interrupts the phase thread when the run is cancelled. The
LLM client already destroys the agent's whole process tree when its
thread is interrupted (the implement watchdog relies on it), so the
in-flight agent call ends at once.

## Motivation

Readiness gap R4 from the Autodev readiness review, §6.4 "Durability,
cancellation, and recovery": "Cancel doesn't kill an in-flight LLM
subprocess."

## Changes in Detail

1. `runner-iteration/interrupt-on-cancel!`: watches the control state for
   the length of one phase step and interrupts the calling thread when the
   run is stopped (including a stop already set when the watch starts).
   Once fired it interrupts again every `cancel-reinterrupt-ms` (200 ms,
   `runner/defaults.edn`) until the phase ends: a handler that swallows one
   interrupt and goes on to another agent call (implement's curator
   fallback) is interrupted again. Ending the watch clears the interrupt
   flag, so the runner's own checkpoint write and pause waits are not
   interrupted afterwards; a lock keeps an interrupt from landing after the
   flag is cleared.
2. `runner-iteration/run-until-cancelled`: runs the phase step under that
   watch. A phase that throws after the cancel interrupted it reports
   `:cancelled?`. Anything thrown without a cancel, and any JVM `Error` in
   the cause chain even after a cancel, is rethrown unchanged, so fatal
   errors still reach the runner's fatal-cause handling.
3. `execute-single-iteration`: a cancelled phase first runs the boundary
   persist (`persist-workspace-at-phase-boundary!`) on what it left in the
   worktree, so post-workflow cleanup does not delete its partial work,
   then stops the run through the existing dashboard-stop path from the
   context the phase started with. A phase that returns despite the
   interrupt keeps its result and is persisted again, since the interrupt
   may have cut short its own boundary persist; the next iteration takes
   the stop path as before.

One change covers both execution paths: DAG sub-workflows run their own
runner loop through `execute-single-iteration` with the parent's control
state (`dag-sub-workflow`), so each task's thread is interrupted on its
own watch.

A resume starts the cancelled phase again, from a task branch that holds
its partial work.

## Testing Plan

`runner_iteration_control_test.clj`:

1. A phase blocked well past the test's wait is interrupted by a cancel;
   the iteration returns `:failed` with a `:dashboard-stop` error, and the
   runner thread's interrupt flag is clear.
2. After a phase returns, a later cancel does not interrupt the runner
   thread (the watch is gone), and no interrupt is left set.
3. A phase that swallows the first interrupt and blocks again is
   interrupted again; the run stops.
4. A `StackOverflowError` wrapped in the exception a phase throws after a
   cancel propagates instead of becoming a stop.

The existing pause-then-cancel test is unchanged.

## Deployment Plan

No configuration. A cancel now ends the current phase immediately instead
of at its end.

## Related Issues/PRs

- Readiness R1–R3: #2287, #2273, #2255, #2261, #2305, #2320, #2333.
