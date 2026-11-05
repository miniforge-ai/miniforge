<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow): GC and spec-path recording honor the run's checkpoint lock

## Overview

Every writer of a run's checkpoint directory now honors the run's checkpoint lock, which the
checkpoint write (#2287) holds while it publishes a generation.

## Motivation

1. The GC classified runs, then deleted each expired one under the run's lock, re-checking only
   the pin marker. A checkpoint write that landed between the two was deleted with the run.
2. `record-spec-path!` read, changed and wrote the manifest without the lock. A checkpoint write
   in between was undone, leaving a manifest that names a generation the write pruned, so the run
   read as having no checkpoint.
3. The lock helper turns an interrupt during its wait into an anomaly, clearing the interrupt
   flag, so a cancel during a checkpoint write's lock wait went unnoticed.
4. `record-spec-path!` took the lock and read the manifest through the root as given, so an alias
   retargeted while it waited could put the manifest it updated under a root it had not locked.

## Changes in Detail

1. `checkpoint-store-gc/delete-run!` re-checks the run's last change under the lock and keeps a
   run written since it was classified, reported under `[:kept :recent]`.
2. `record-spec-path!` resolves the root once and holds the run's lock for its read and write,
   reading the manifest through the resolved run directory. The root and run directory are checked
   as given (`directory/existing!`: ancestry and link ownership) before their real paths are used,
   as a checkpoint write checks them. A pending interrupt is held off for
   the wait and the write and restored after; an interruption becomes a write-failure anomaly with
   the flag set again; a lock that stays busy is an anomaly.
3. `persist-execution-state!` and `record-spec-path!` set the interrupt flag again when the lock
   helper reports an interruption as its anomaly (`interrupted-anomaly?`).
4. `finish-spec!` (CLI) warns on stderr when the manifest could not be updated, since a later
   resume would look for the spec at its old place.

## Testing Plan

In `components/workflow/test/ai/miniforge/workflow/checkpoint_generation_test.clj`, each failing on
the code before its fix (the alias-retarget test waits for the recorder to ask for the lock before
retargeting):

1. `a-write-between-gc-classification-and-delete-keeps-the-run`
2. `recording-the-spec-path-waits-for-the-run-lock`
3. `recording-the-spec-path-holds-off-a-pending-interrupt`
4. `recording-the-spec-path-keeps-the-root-it-locked`
5. `recording-the-spec-path-reports-lock-failures`
6. `recording-the-spec-path-checks-the-root-as-given`: an alias in a world-writable directory is
   refused
7. `an-interrupt-the-lock-wait-consumed-is-kept`

`spec_kanban_tracking_test/a-manifest-that-could-not-be-updated-is-warned-about` covers the CLI
warning. Ran the GC, pin-race, checkpoint, spec-hash and kanban tracking tests.

## Deployment Plan

No configuration or data migration.

## Related Issues/PRs

Follows #2287 (each checkpoint write is one generation, published under the run lock).

## Checklist

- [x] Regression tests fail on the previous code
- [x] Independent review and pinned-standards check
- [ ] Copilot threads answered and resolved, CI green, no conflicts
- [ ] Merged
