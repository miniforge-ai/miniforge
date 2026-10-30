<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow,cli): one live process per run (owner lock)

## Overview

A run is executed by at most one live process. `run-pipeline` takes an exclusive owner lock on
the run id for the run's lifetime; `mf resume` and the resume launcher refuse a run whose lock is
held.

## Motivation

`resume.clj` took no lock, a resume could continue a live run under its own id, and the launcher
checked liveness with nothing held between the check and the start, so two processes could run
one run and write the same checkpoints.

## Changes in Detail

1. `workflow/run-owner-lock` (new): one file per run under `<checkpoint-root>/.owners/`, locked
   with a non-blocking `tryLock` and held until the run ends. A busy lock is a `:conflict` anomaly
   naming the owner (run id, pid, start time). The OS drops the lock when the owner dies. A
   process-local registry answers claims and probes for locks this process holds, since closing
   any descriptor of a file releases the process's POSIX locks on it. Another process's probe holds
   the lock for an instant, so a claim that meets it tries once more before refusing. A symlinked
   lock file or directory is refused. An interruption while taking the lock sets the interrupt flag
   again.
2. Paths: `acquire!` makes the root with the checkpoint directory helper (`directory/prepare!`:
   private, protected ancestry, not the process umask) and names the lock file from the root's
   real path, so any spelling of a root (links, `..`) locks one file. A root that does not exist as
   spelled is left as spelled: normalized, `<dir>/unused/../alias` would reach the held file through
   the link, and closing it would release this process's lock. `physical-root` returns the real
   path.
3. `run-pipeline` pins the root's physical path at start and uses it for the lock, the context
   (`:checkpoint/root` and `:execution/checkpoint-root`) and a restored snapshot's
   `:execution/checkpoint-root`, so retargeting an alias mid-run cannot move checkpoints away from
   the lock. The snapshot's root is used only when the context is actually restored (snapshot and
   phase results both given).
4. `mf resume` refuses a run whose owner lock is held, naming the owner process, before
   reconstructing anything (`refuse-live-run!`). A root or lock that cannot be looked up refuses
   the resume too; with `--force`, a root that cannot be looked up is checked at the default root
   after a warning. The runner still takes the lock itself before it starts.
5. The launcher's `target-live?` counts a held or unreadable owner lock as a live runner.
6. `run-checkpoint-root` passes over unreadable event files (every announcement of one run names
   the same root) and returns an `:unavailable` anomaly when none reads and one did not, instead of
   falling back to the default root.

## Testing Plan

1. Recovery scenario, `components/workflow/test/ai/miniforge/workflow/run_owner_lock_test.clj`: a
   claim held in this process or in a separate `bb` process is refused and allowed once released;
   probes through other spellings of the root keep another process's view of the lock; a root the
   lock creates is private; a probe through a spelling with a missing segment opens nothing; a
   claim that meets a probe tries again; `run-pipeline` refuses a
   running run and pins the physical root. The cross-process cases fail, not skip, when `bb` is
   not on PATH.
2. `resume_test/a-running-run-is-not-resumed-test` fails on main (the resume proceeded); it also
   covers an unknown root with and without `--force`.
3. `checkpoint_store_gc_test/root-lookup-passes-over-an-unreadable-event`;
   `resume_pin_root_test` now expects a root that cannot be looked up to refuse the resume.
4. Ran the workflow runner, owner-lock, checkpoint and resume suites (388 tests) and cli resume
   tests; `bb test:graalvm` passes in the pre-commit hook.

## Deployment Plan

No migration. Owner lock files appear under existing checkpoint roots. A checkpoint root the
directory policy refuses (for example group-writable) now stops the run from starting; before, the
run started and every checkpoint write failed.

## Related Issues/PRs

Builds on #2094 and #2244 (protected checkpoint directories) and #2256 (run lineage).

## Notes

1. Owner files are not pruned (tens of bytes per run).
2. `resume.clj` spans 4 layers on main (SL003); commits touching it used
   `MINIFORGE_STRATUM_BUDGET_MODE=warn`, adding no layer.

## Checklist

- [x] Regression tests fail on the previous code
- [x] Independent review and pinned-standards check
- [ ] Copilot threads answered and resolved, CI green, no conflicts
- [ ] Merged
