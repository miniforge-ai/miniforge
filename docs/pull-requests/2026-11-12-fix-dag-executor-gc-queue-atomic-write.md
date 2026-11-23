<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(dag-executor): write the scratch GC queue atomically

## Overview

`scratch-gc-queue/write-gc-queue!` wrote the queue file in place with a
bare `spit`. It now writes a sibling temp file and moves it over the
queue with one atomic rename.

## Motivation

A process killed during the in-place write left a truncated queue. The
next read treated that file as corrupt, set it aside and reset the
queue to `[]`. Every pending entry was lost, so those scratch refs were
never collected.

## Changes in detail

- `write-gc-queue!` writes `<queue>.tmp-<uuid>` beside the queue, moves
  it over the queue with `ATOMIC_MOVE`, and deletes the temp file in a
  `finally`.
- The queue lock is held on the `<queue>.lock` sidecar, not on the queue
  file. Replacing the queue file does not release it.
- The docstring of `quarantine-corrupt-queue!` still says the lock is
  held on the queue file. That was already false before this change and
  is left for the namespace split below.

## Known follow-up

`scratch_gc_queue.clj` already has four layers on `main`, one over the
budget of three (SL003). This change adds no layer. The local commit
gate blocks any further edit to the file until the namespace is split.
The split and the docstring correction belong in their own PR.

## Testing plan

`scratch_gc_queue_test.clj` adds two tests:

- A second write replaces the queue whole and leaves no temp file.
- A write that puts down partial content and then throws leaves the
  previous queue readable and leaves no temp file.

The second test errors against the previous bare `spit` and passes with
this change. The namespace runs 17 tests and 58 assertions with no
failures.

## Deployment plan

No configuration or data change. The queue file keeps its name and
format.

## Related issues/PRs

Based on `main`. No dependencies.

## Checklist

- [x] Regression test fails before the fix and passes after it
- [x] Merged with current `main`
- [ ] CI green on the final head
- [ ] Review threads resolved
