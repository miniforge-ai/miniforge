<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: verify's local test runs and the agent CLI readers use the bounded capture

## Overview

Two more readers of child-process output move onto the `subprocess`
component's 64 MiB per-stream limit: the verify phase's local test runner,
and the `llm` readers of the agent CLI's stdout and stderr. A local test run
whose output passes the limit is stopped and fails. A cut agent CLI stream
keeps its head and ends with the cut note.

## Motivation

`verify/run-tests!` ran the test command with `:out :string :err :string`,
which reads both streams whole. In local mode, agent-written code under test
that prints in a loop would fill the JVM heap during the 30-minute test
deadline.

The governed agent CLI runs on the host: `:execution/exec-fn` is set in
`runner_preparation` but nothing reads it. Its output therefore goes through
`llm`'s `read-process-stream!`, which slurped each stream whole: stderr on
every call, and stdout on the non-streaming capture path.

## Changes in Detail

1. `subprocess`: `read-bounded`, `drain-stream` on the calling thread, for a
   caller that already reads on a thread of its own.
2. `llm` `read-process-stream!` reads through `read-bounded` on its daemon
   thread. The thread stays a daemon thread, so a pipe an orphan holds open
   cannot keep the JVM alive.
3. `verify/run-tests!` drains stdout and stderr with an `:on-overflow` that
   destroys the test process tree. `exited-test-result` turns a cut run into a
   failed result whose message names the limit and the command and shows the
   first `verify-error-preview-limit` characters of output. That message does
   not contain `timed out`, so leave-verify redirects to implement: output that
   size comes from the code under test.
4. Registration: `llm` and `phase-software-factory` `deps.edn`, and the
   `deliberation-lab` project.

## Behavior changes

1. A local test run that prints more than 64 MiB on either stream fails
   before its deadline, and implement sees the start of the output.
2. An agent CLI stream past 64 MiB reaches the parser cut, ending in
   `[output cut at … bytes; the rest was discarded]`.

## Not covered

1. `stream_exec` keeps every streamed stdout line in memory. Its stagnation
   and total-time limits bound it in practice, and model output does not run
   away the way a test loop can.
2. The drain treats every failed read, `InterruptedIOException` included, as
   end-of-stream. Neither caller here needs to tell a read failure apart.

## Testing Plan

1. `subprocess` `read-bounded-test`: the head, overflow mark and one hook
   call past the limit; the configured default otherwise.
2. `llm` `stream-capture-limit-test`: with a 16-byte limit, the atom holds the
   head and a note naming the limit; short input arrives whole.
3. `verify-output-limit-test`: `yes` as the test command with a 1 KiB limit
   fails within 5 seconds, not at its 10-second deadline, with the
   output-limit message and the start of the output.
4. Existing `llm` capture-process and interface tests and the verify,
   failure-mode and interrupt tests pass. Locally: 121 tests, 551 assertions,
   0 failures.

## Deployment Plan

None.

## Related Issues/PRs

Depends on #2466 (the `subprocess` component). Based on its branch,
`feat/subprocess-capture`; retarget to `main` once it merges.

## Checklist

- [x] verify local test runner bounded and failing past the limit
- [x] llm stream readers bounded
- [x] Tests for each
