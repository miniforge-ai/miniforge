<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Release ignores what verify and review leave on the task branch

## Overview

Release decides whether an `:already-implemented` task has work to ship by
diffing the task branch against its base commits (#2305). Every phase's
boundary commit is on that branch, including verify's and review's. A file
either of them left in the worktree (a regenerated lockfile, a coverage
report, a file a test run rewrote) is committed at their boundary and made
release publish it as the task's work.

Each boundary commit now names, in `Miniforge-Phase` trailers, every phase
whose work it holds. Release counts a committed path only when a commit
other than a verify or review boundary commit touched it.

## Motivation

The intended outcome for "already implemented, nothing new" is a skip. A
checking phase's by-product turned it into a PR holding only that file, or a
release failure on it.

## Changes in Detail

1. `phase.interface.boundary-commit` (new): the trailer key and
   `with-phase-trailers`, shared by the workflow (writer) and release
   (reader).
2. `runner-environment/persist-workspace-at-phase-boundary!` labels the commit
   with the phase that ran, read from the pre-step context, and appends the
   trailer. It used to read the post-step context, whose current phase has
   already advanced: implement's commit was labelled "verify phase
   completed", and a review that redirects back to implement labelled its
   commit "implement". The gate-denial check already read the pre-step
   context; the persist's log events and notices now name the same phase.
3. A persist that was skipped, rejected or threw leaves its phase's work in
   the worktree. `runner-environment/track-unpersisted` keeps that phase in
   `:execution/unpersisted-phases` until a persist succeeds, and the next
   boundary commit names it as well: implement's work committed at verify's
   boundary carries both trailers and still counts.
4. `release-scan/base-diff` also runs
   `git log -z --format=%H%x00<trailers> <base>..HEAD`. A commit is a checking
   commit when every phase it names is verify or review. When there is one,
   each other commit's paths are read with `git diff-tree -z <sha>^ <sha>`
   (first parent, so a merge counts what it brought in), and only diff
   entries on those paths are kept. Paths are only ever read from
   NUL-separated output, so any file name is matched exactly. A commit with
   no trailer (the agent's own, or a boundary commit made before this
   change) counts, so no earlier run loses work. A failed `git log` or
   `diff-tree` is a `:release/git-status-failed` anomaly, like every other
   scan failure.
5. Only verify and review are excluded. Plan, explore and an unlabelled
   phase still count: a wrongly counted file is a visible PR, while a wrongly
   excluded one is silently dropped work.

The excluded commits are not removed from the branch. When release does
publish (implement did real work), they ship with it as before; they only
stop deciding whether there is anything to publish, and stay out of the
files that feed the PR metadata.

## Testing Plan

`release_checking_commits_test.clj` (real repos):

1. Only verify and review boundary commits beyond the base: release skips.
2. Implement's commit (a path git would quote) plus a verify by-product: only
   implement's file is released.
3. A file implement wrote and verify rewrote is released with the branch's
   content.
4. A commit without a trailer counts.
5. Capsule scan under real `sh -c`: the quoted `--format` argument works and
   a review by-product is left out.
6. A failing `git log` raises `:release/git-status-failed`.
7. Implement's work in a commit naming implement and verify is released.
8. A path holding an ASCII record separator is released.
9. A file only a merge brought in is released; the verify by-product
   beside it is not.

Workflow tests: the commit message carries the trailer of the phase that
ran, while the post-step context names the next phase; a failed or skipped
persist's phase is named by the next commit and cleared by a successful one.

## Deployment Plan

No configuration or migration. Runs started before this change have
trailer-less boundary commits, which count as before.

## Related Issues/PRs

- #2305, #2320: release counts committed work since the task base.
