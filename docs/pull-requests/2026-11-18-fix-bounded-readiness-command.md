<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: bound read-only executor commands by an absolute deadline

## Overview

Provide the internal command boundary needed to bound Kubernetes readiness
polling. It accepts an argument vector and one monotonic deadline.

## Motivation

A provider request timeout alone does not stop a hung local CLI process.
Waiting for output also needs to share the acquisition deadline.

## Changes in detail

Reuse the executor's process-tree cleanup and bounded stream capture.
Refuse an expired call before spawning, bound process and output waits,
and stop the owned process on timeout or cancellation. Timeout results
discard partial output. Cleanup may use the shared shutdown grace twice:
once for graceful termination and once to confirm forced process exit.
The shared tree helper defers cancellation through forced termination.
Interrupts do not restart either shutdown deadline.
The dependent merge exposed prose-gate failures in three incoming PR
documents. Their flagged sentences are split without changing the claims.

## Testing plan

Use disposable local subprocesses, not Kubernetes. Cover success, expired
admission, a hung process, cancellation and capture overflow. Verify that
the directly launched process is no longer alive when the call returns.
Run strict lint, actual namespace depths and both product runtimes.
Both JVM products, Babashka source and a freshly built package passed
7 integration tests / 20 assertions each. This includes cancellation
during cleanup of a process that ignores SIGTERM. The process tests use a local
POSIX shell; no provider or cluster is contacted.
Removing interruption deferral fails two cleanup assertions without a
runtime error. Existing executor and fake-runtime suites passed 82 tests /
301 assertions against the shared helper.

## Deployment plan

No live provider calls or new authority. This internal prerequisite does
not change workload dispatch; #2536 will wire readiness to it.

## Related issues/PRs

Based on main. Required by #2536; merge this prerequisite first.
