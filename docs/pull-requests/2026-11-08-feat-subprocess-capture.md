<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: subprocess component bounds captured child output; dag-executor uses it

## Overview

A new `subprocess` component drains a child process's stdout or stderr into
memory with a size limit. Every drain in dag-executor's runtime CLI calls and
the worktree executor's `execute-command` now goes through it. When a
container command passes the limit, the runtime CLI is stopped and the call
fails.

## Motivation

Every drain in dag-executor copied a child's whole output into a growable
`ByteArrayOutputStream` (`drain-stream`) or `.readAllBytes` (`read-stream-future`).
Governed runs send test commands and capsule file reads through `docker exec`,
so agent-written code under test that prints in a loop would fill the heap
until the JVM failed. That takes every parallel task, the
dashboard and the event stream with it. The exec deadline (5 minutes) does not
help, because a fast writer fills the heap long before it.

The limit is 64 MiB per stream. The largest miniforge agent session
transcript measured on 2026-11-08 was 8.4 MB, across 109 task runs (median
445 KB), so legitimate output stays well below it.

## Changes in Detail

1. New component `subprocess` (interface `ai.miniforge.subprocess.interface`):
   - `drain-stream` keeps at most `:max-bytes`, reads and discards the rest so
     the child never blocks on a full pipe, marks the handle overflowed, and
     calls `:on-overflow` once.
   - `drained-text` ends in a note when the stream was cut; `drained-bytes`
     returns the kept bytes only.
   - `overflowed?`, `cancel-drain!`, `output-limit-exit-code` (141, 128 +
     SIGPIPE).
   - The limit is `:subprocess/max-captured-bytes` in
     `config/subprocess/capture.edn`, checked against a Malli schema at load.
     A missing or malformed file, or a value that is not a positive integer
     a byte buffer can hold, stops startup.
2. dag-executor `runtime.process`: its drain functions now come from the
   component (`read-stream-future` reads through a bounded drain), so every
   executor that drains through this namespace is bounded.
3. dag-executor `oci-cli.process`: `run-runtime-timed` and
   `run-runtime-process-timed` drain with an `:on-overflow` that destroys the
   runtime CLI's process tree. A call cut that way returns exit 141, empty
   output and the `:oci/output-limit` message, naming the limit that was
   passed. This covers `exec-in-container`: capsule test runs and file reads.
   The governed agent CLI still runs on the host (`:execution/exec-fn` is set
   in `runner_preparation` but nothing reads it), so its output goes through
   the `llm` subprocess reader, which the follow-up below covers.
4. The worktree executor's `execute-command` keeps the head of a cut stream
   and the note, and runs the command to its own end or deadline.
5. Registration: root `deps.edn` (`:dev`, `:test`), the bb `miniforge` task,
   the `miniforge`, `miniforge-core`, `miniforge-tui` and `data-foundry`
   projects, and `dag-executor/deps.edn`.

## Behavior changes

1. A container command whose stdout or stderr passes 64 MiB fails with exit
   141, and its `docker exec`/`podman exec` CLI is stopped. The process
   inside the container is not a child of that CLI and runs on until the
   container is released, as it already did on a timeout. That includes `copy-from!` of a file over 64 MiB
   and an image build that prints more than 64 MiB. Before, both read the
   whole stream into memory.
2. Worktree-executor output past 64 MiB is cut to its head and ends with
   `[output cut at … bytes; the rest was discarded]`.

## Design notes

`worktree.clj` (7 layers) and `runtime/descriptor.clj` (6 layers) are over
rule 210's layer budget, and the commit hook rejects either file once it is
staged. Their callers keep calling `runtime.process`, which now delegates to
the component. That is why the worktree executor does not stop a child at the
limit: doing so means editing `worktree.clj`, which waits for its namespace
split.

The component reads and discards past the limit rather than closing the
pipe. Closing would kill most writers with SIGPIPE, but the JVM ignores
SIGPIPE, so a Java test runner would keep running anyway. Each caller decides
whether to stop the child.

A read that fails ends a drain like end-of-stream, keeping what was read.
`Process.destroy` closes all three of a child's streams, so stopping a child
for one stream's overflow makes both drains' next reads throw `Stream
closed`. The end-to-end test caught that: before this rule, both runners
returned exit 1 with that IOException instead of the output-limit failure.

## Testing Plan

1. `subprocess` `interface-test`: under, at and over the limit; the kept head;
   one `:on-overflow` call; the cut note; the configured default; cancelling
   a drain parked on a pipe. `config-test`: absent, valid and invalid limits.
2. `oci-cli-test/runtime-runners-stop-a-child-past-the-capture-limit-test`:
   `/usr/bin/yes` through both runners, with a 1 KiB limit, is stopped and
   returns exit 141 with the output-limit message. A writer that ignores
   SIGTERM and SIGPIPE and loops past failed writes is stopped by the SIGKILL
   that follows the one-second grace, and the call returns within 5 seconds.
   Without the SIGKILL it would run to the 10-second deadline.
3. `worktree-execute-test/execute-command-marks-output-cut-at-the-capture-limit-test`:
   4 KiB of output with a 1 KiB limit keeps 1 KiB and ends with the note.
4. Existing runtime, container, fake-runtime, security-gate and worktree
   execute tests still pass.
5. `bb check:bb-classpaths`: every bb task's entry namespaces load,
   including `miniforge`.

## Deployment Plan

None. The limit applies from the next run.

## Not covered

The worktree executor's `run-git` and `run-shell` call `clojure.java.shell/sh`,
which reads whole outputs, as do about 70 other `sh`/`:out :string` calls
across the codebase. Most run git or gh on the host. They are not part of
this change.

## Follow-ups

1. The verify phase's local test runner (`verify/run-tests!`) and the agent
   CLI's stderr reader in `llm` still read whole streams. A second PR moves
   them to this component. The drain treats every failed read, including an
   `InterruptedIOException`, as end-of-stream; if those callers need to tell
   a read failure apart, the handle should record it.
2. Once `worktree.clj` and `descriptor.clj` are split, their drains can call
   the component directly and the worktree executor can stop a child at the
   limit.

## Related Issues/PRs

From the PAODO-Workspaces review. PAODO keeps every memory ceiling in one
file so whether "every accumulating path is bounded" can be checked in one
place. #2462 (`--pids-limit`) is the other change from that review.

## Checklist

- [x] Component, config, schema and tests
- [x] dag-executor runtime and worktree executors drain through it
- [x] Registered in deps.edn, bb.edn and projects
