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
holds beyond the task's base commits: the shas implement recorded, then the
environment's base commit.

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
   from the task's base commits. A failed scan comes back as its anomaly
   (as merged, `skip-probe` threw it; the follow-up below returns it again).
2. Base commits come from `task-base/recorded-refs`, then
   `[:execution/environment-metadata :base-sha]`. The `:no-secrets` gate uses
   the same resume-aware refs. Checkpointed refs preserve the first attempt's
   base on resume, even when the acquired worktree already contains earlier commits.
3. Only exact commits are diffed. `git diff <ref>` compares the working tree
   with the ref itself. Branch names, ranges and the CLI's starting commit
   could count upstream changes as this run's work. That would turn a no-op
   into a release failure.
4. New `release-scan` namespace does the committed scan, on the host or in the
   capsule:
   - `git diff --raw --no-renames -z` preserves non-ASCII paths without git's
     C-style quoting. Each entry also carries its git mode.
   - Only regular files (modes 100644/100755) are read. A symlink, a submodule
     or a deletion carries its path and action with empty content. File
     content feeds PR metadata and the releaser's prompt, never a write-back.
     A symlink's target is never read into that metadata. The host reader also refuses a path whose canonical target
     leaves the worktree.
   - The first available base commit decides, including an empty diff.
     Later refs provide fallbacks for missing commits (`rev-parse --verify`
     exit 1). Work a repair reverted is
     not counted against a newer environment base.
   - Every failure (git exit, executor error, unreadable regular file) is a
     `:release/git-status-failed` anomaly, never "no changes".
     `build-workflow-state` raises it unless implement's recorded files carry
     the release.
   - `command-failure` and `git-status-failed` reside in `release-scan`.
     Qualified calls replaced the private aliases. Scanning helpers remain
     separate from the release interceptor.
5. Committed-only work then takes the release executor's existing
   `:preexisting-commits` path, as the boundary-commit case already does.

## Testing Plan

`release_committed_work_test.clj`:

1. `:already-implemented`, clean worktree, one commit beyond the base: release
   prepares, and the committed file is in the release artifact (real repo).
2. Resumed run: environment base at HEAD, implement's recorded base refs name
   the earlier commit: the committed file is released (real repo).
3. Clean worktree at its base commit: release skips as before (real repo).
4. A committed `src/été b.clj`, which git quotes without -z, is released
   (real repo).
5. A base sha the repository does not have is passed over; release skips as
   before (real repo).
6. Capsule command failures raise `:release/git-status-failed` instead of
   skipping when implement has no recorded files. With recorded files,
   release prepares from those files instead.
7. A committed symlink to a file outside the worktree is released by path
   with empty content; the target's content appears nowhere (real repo).
8. A committed submodule is released by path, not raised (real repo).
9. Resume after a repair reverted the work: the recorded base diffs clean and
   release skips, though the newer environment base differs (real repo).

Existing skip tests carry no base commit and keep their behaviour.

## Deployment Plan

No configuration or migration. A release that used to skip as
`:already-implemented` over committed work now prepares and publishes it.

## Related Issues/PRs

- #2255, #2261: the retry and duplicate-PR parts of R3.
- Follow-up `fix/release-scan-followups`: post-merge review fixes (governed
  runs scanned the host worktree path instead of the capsule checkout; a
  literal user-facing string; see its PR doc).

## Checklist

- [x] Regression test for the drop path
- [ ] No user-facing strings added (as merged, `release_scan.clj` built one
      inline; the follow-up moves it to the messages catalog)
- [x] Stratum metadata consistent in edited namespaces
