<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: a bounded retry-verification pool that never fails a launched run

## Overview

Review fixes for `feat/operator-async-resume` (#1914), split out because they
would take that PR past the 600-line gate. It sits between #1914 and
`feat/resume-launcher` (#1915).

## Motivation

Review of #1914 found three problems in the verification pool:

- The pool was a cached thread pool. A burst of retries, each waiting for its
  run to start, could take any number of threads.
- `submit-verification!` could hand work to a pool that `stop-verifications!`
  had just shut down. The rejection surfaced as `:application-error`, failing
  an intervention whose run had launched and leaving its launch unsettled.
- The launch returned by `:await-start!` was dropped: the readback and
  `:settle!` used the launch as first reported.

## Changes in Detail

- The pool is fixed: 4 threads and a queue of 64. When both are full, a
  submission runs nothing (a discard policy: Babashka, which loads the
  operator, has no `RejectedExecutionException`). The intervention stays `:dispatched` and its launch
  unsettled, and the launcher's recovery at the next start
  (`verify-launched-resume!`) finishes it. A launched run is never failed for
  want of a thread.
- A submission and a stop's swap of the pool hold one lock, so no submission
  reaches a pool that is being shut down. After a stop, or on a pool found
  shut down, a submission starts a fresh pool.
- A stop drops work that has not started and drains running work as before.
  Neither records anything: both stay `:dispatched` for the next start.
- The launch `:await-start!` returns is read back (its run id) and handed to
  `:settle!`. `verify-launched-resume!` does the same.

## Testing Plan

- `application-test`: a wait that returns an enriched launch is read back and
  settled with it.
- `application-test`: a submission that finds the pool shut down (what a
  submission racing a stop saw) lands on a fresh pool and verifies.
- `application-test`: with one thread and a queue of one, a third retry is left
  `:dispatched` and never runs; a stop runs no queued verification and settles
  nothing.
- Each test failed before the fix. Pre-commit hook per commit.

## Deployment Plan

No migration.

## Related Issues/PRs

Stack: `feat/operator-async-resume` (#1914), this PR, `feat/resume-launcher`
(#1915), `feat/resume-launcher-hardening`, `feat/shared-process-handles`,
`feat/operator-serve`.

## Checklist

- [x] Tests for new behaviour
- [ ] Review comments addressed; CI green
