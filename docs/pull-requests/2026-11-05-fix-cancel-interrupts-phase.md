<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Cancel stops the agent subprocess in flight

## Overview

`mf workflow cancel` (and the dashboard and TUI stop) sets `:stopped` on the
run's control state. The runner read it only between phases, and the DAG
orchestrator between batches, so a cancelled run kept going until the
current phase finished: the agent CLI, its MCP servers and its Bash
children ran on, possibly for many minutes, editing the worktree and
spending tokens after the operator said stop.

The runner now gives every LLM call a phase makes a cancel check. The
streaming client reads it in its stream loop (every
`:stream :poll-interval-ms`, 250 ms by default): once the run is cancelled
it destroys the agent's process tree and returns a `:cancelled` timeout,
and a call made after the cancel returns the same way as soon as it
starts. Nothing is interrupted, so the phase finishes its own flow,
executor cleanup and boundary persist included, and the next iteration
takes the existing stop path.

## Motivation

Readiness gap R4 from the Autodev readiness review, §6.4 "Durability,
cancellation, and recovery": "Cancel doesn't kill an in-flight LLM
subprocess."

An earlier revision of this PR interrupted the phase thread instead.
Review showed the interrupt reaching code that must not be interrupted:
executor process teardown, DAG batch draining (which then cancels child
futures), and boundary persistence. Scoping the cancel to LLM calls
avoids all of them.

## Changes in Detail

1. `llm.cancellation` (new): the dynamic cancel check,
   `call-with-cancel-signal` to bind it, and `cancelled-timeout`, the
   adaptive-timeout reason (`:type :cancelled`) for a cancelled run.
   Exported through `llm.interface/call-with-cancel-signal`.
2. `stream-exec/process-stream-lines`: checks the cancel before the
   progress monitor's timeouts on every poll. A cancelled stream ends like
   any other adaptive timeout: the process tree is destroyed and the call
   returns a timeout result.
3. `subprocess/wait-for-stream-process`, the wait every agent CLI call
   ends in (the streaming client after its stream closes, and the
   non-streaming capture client), now waits in poll-interval slices and
   checks the cancel between them: a CLI that closed stdout but did not
   exit, or a non-streaming call, is killed with its tree when the run is
   cancelled, and the result carries the `:cancelled` reason.
4. `runner-iteration/execute-supervised`: runs the phase step under a
   cancel check that reads the run's control state. DAG sub-workflows run
   their own iterations against the parent's control state, so their calls
   see the cancel too; futures a phase starts inherit the binding.
5. A new message, `:stream.system/run-cancelled`, carries the reason text.

`:cancelled` is not one of the infra-timeout types the agents retry
(`:stream-idle`, `:stagnation`, `:hard-limit`) or auto-resume
(`:network-drop`), so a cancelled call reads as a failed call; the phase
ends and the run stops at the next iteration.

Scope: this stops LLM subprocesses. Non-LLM work a phase is doing when the
cancel lands (a test run in verify, a git command) still finishes. An LLM
call made on a thread that does not inherit Clojure bindings (a raw
`Thread`) does not see the cancel.

## Testing Plan

`stream_process_cleanup_test.clj` (real processes):

1. A streaming call whose run is cancelled mid-stream returns a
   `:cancelled` timeout without throwing, and neither the shell nor its
   10-minute child is left running.
2. A call made after the cancel returns a `:cancelled` timeout at once and
   leaves nothing running. Its processes are found by a unique token in
   their command lines, since the cancelled call never reads the pids they
   print.
3. A cancel while waiting for a CLI that closed stdout but did not exit
   ends the wait with a `:cancelled` timeout and kills the tree.

`runner_iteration_control_test.clj`: the phase step runs under a cancel
check that is false while the run is live and true once it is cancelled.

## Deployment Plan

No configuration. A cancel now ends the current agent call within one
stream poll; the phase then finishes and the run stops.

## Related Issues/PRs

- Readiness R1–R3: #2287, #2273, #2255, #2261, #2305, #2320, #2333.
