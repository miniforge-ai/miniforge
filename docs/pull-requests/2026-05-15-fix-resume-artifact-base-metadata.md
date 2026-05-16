# Fix: Resume Artifact Base Metadata

## Overview

This PR fixes a dogfood resume failure where a restarted local worktree could
produce a clean committed task state, but artifact fallback still reported that
the implementer wrote no files.

## Motivation

Checkpoint `3927baf8-c9db-44d3-b5fb-5a1552dbe554` resumed into `implement` and
failed with `Curator: implementer wrote no files to the environment`. The task
worktree fallback depends on a base ref so it can diff committed task work
against the branch it was created from. Resumed snapshots were not persisting
environment metadata, and freshly acquired default-branch worktrees did not
thread the resolved branch into execution opts.

## Changes in Detail

- Persist `:execution/environment-metadata` in durable machine snapshots.
- Preserve checkpoint environment metadata when resume does not supply fresh
  metadata.
- Thread the branch used for worktree acquisition into `:execution/opts`.
- Enable clj-kondo cache lookups for staged-file lint so local namespace vars are
  resolved without inline suppressions.
- Cover default-branch threading and checkpoint metadata preservation in runner
  tests.

## Testing Plan

- `clj-kondo --lint` on changed workflow source and tests: 0 warnings.
- `bb lint:clj` on staged source and tests: 0 warnings.
- Focused runner tests: 27 tests, 84 assertions, 0 failures, 0 errors.
- `bb test` stable-derived plan across 4 projects: passed.

## Deployment Plan

No migration is required. Existing checkpoints without environment metadata will
continue to resume as before; new checkpoints retain the metadata needed for
artifact diff fallback.

## Checklist

- [x] Checkpoint snapshots retain environment metadata.
- [x] Resume preserves checkpoint metadata when fresh metadata is absent.
- [x] Default local worktree branches are available to artifact recovery.
- [x] Stable-derived tests passed.
