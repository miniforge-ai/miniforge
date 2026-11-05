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
   Ending the watch clears the interrupt flag it set, so the runner's own
   checkpoint write and pause waits are not interrupted afterwards. A lock
   keeps a cancel racing the end of the phase from interrupting the thread
   after the flag is cleared.
2. `runner-iteration/run-until-cancelled`: runs the phase step under that
   watch. A phase that throws after the cancel interrupted it reports
   `:cancelled?`; anything thrown without a cancel is rethrown unchanged.
3. `execute-single-iteration`: a cancelled phase stops the run through the
   existing dashboard-stop path, from the context the phase started with.
   A phase that returns despite the interrupt keeps its result; the next
   iteration takes the stop path as before.

One change covers both execution paths: DAG sub-workflows run their own
runner loop through `execute-single-iteration` with the parent's control
state (`dag-sub-workflow`), so each task's thread is interrupted on its
own watch.

A phase cut short does not run its boundary persist. Its partial work stays
in the worktree, uncommitted, as it would after a crash; a resume starts
the phase again.

## Testing Plan

`runner_iteration_control_test.clj`:

1. A phase blocked well past the test's wait is interrupted by a cancel;
   the iteration returns `:failed` with a `:dashboard-stop` error, and the
   runner thread's interrupt flag is clear.
2. After a phase returns, a later cancel does not interrupt the runner
   thread (the watch is gone), and no interrupt is left set.

The existing pause-then-cancel test is unchanged.

## Deployment Plan

No configuration. A cancel now ends the current phase immediately instead
of at its end.

## Related Issues/PRs

- Readiness R1–R3: #2287, #2273, #2255, #2261, #2305, #2320, #2333.
