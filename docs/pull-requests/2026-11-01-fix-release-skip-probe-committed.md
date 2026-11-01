<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Release does not skip work the task branch already committed

## Overview

Release skips when implement reports `:already-implemented` (or plan
`:already-satisfied`) and there is nothing to release. It decided "nothing to
release" from `git status` alone, so committed work on a clean worktree was
skipped and never pushed. The skip check now also counts files the task branch
holds beyond the environment's base commit.

## Motivation

In a repair loop, implement iteration 1 writes files and the boundary commits
them. The reviewer requests changes; iteration 2's implementer judges the work
done and reports `:already-implemented` with no new files. The worktree is
clean, the skip check saw no changes, and release completed as skipped. Iteration
1's commits never reached a branch or a PR.

This is the remaining drop path from the release-idempotency item (R3) of the
Autodev readiness review; #2255 and #2261 closed the retry and duplicate-PR
parts.

## Changes in Detail

1. `release.clj`: `skip-probe` runs the existing dirty scan. When that comes
   back clean it adds `committed-since-base`: the files the worktree differs by
   from the task's base commits, read through `agent/collect-worktree-files`
   (or its capsule twin in governed mode) and filtered with
   `agent/substantive-file?`. A failed dirty scan still returns its anomaly.
2. The base commits are the commit shas among the task base refs implement
   recorded (`task-base/recorded-refs`, the resume-aware base the `:no-secrets`
   gate already uses), then `[:execution/environment-metadata :base-sha]`.
   The recorded refs are checkpointed, so a resumed run, whose environment is
   acquired on a worktree already holding its earlier commits, still measures
   from the first attempt's base.
3. Only exact commits are diffed. `git diff <ref>` compares the working tree
   with the ref itself, so a base branch name, a range, or the commit the CLI
   started on would count upstream changes as this run's work and turn a no-op
   into a release failure.
4. Committed-only work then takes the release executor's existing
   `:preexisting-commits` path, as the boundary-commit case already does.
5. `agent` interface exposes `collect-worktree-files-via-executor`.

## Testing Plan

`release_committed_work_test.clj`, on a real git repo:

1. `:already-implemented`, clean worktree, one commit beyond the base: release
   prepares, and the committed file is in the release artifact.
2. Resumed run: environment base at HEAD, implement's recorded base refs name
   the earlier commit: the committed file is released.
3. Clean worktree at its base commit: release skips as before.

Existing skip tests carry no base commit and keep their behaviour.

## Checklist

- [x] Regression test for the drop path
- [x] No user-facing strings added
- [x] Stratum metadata consistent in edited namespaces
