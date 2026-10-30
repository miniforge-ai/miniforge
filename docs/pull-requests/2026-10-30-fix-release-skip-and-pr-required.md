<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(release): release unpushed commits; require the PR when asked for

## Overview

A release could end `:completed` with its work unpublished. This PR closes the two remaining
paths: the skip probe now finds committed but unpublished work, and a release asked to open a PR
fails when none was opened.

## Motivation

1. The skip probe (implement status `:already-implemented`, `:already-satisfied` or nil) checked
   only `git status`. Work the implement boundary had committed but no push had published read
   as nothing to release and was skipped.
2. A publish that succeeded without a PR while `:create-pr?` was set still completed.
3. An explicit `:create-pr? false` in `:execution/opts` fell back to the config default (true).

## Changes in Detail

1. `release-unpushed/unpushed-files` (new namespace): the files changed by commits on HEAD that
   are on none of the task's base commits and on no origin branch except the run's own
   (`git log -m --no-renames --ignore-missing --name-only -z HEAD --not <base commits>
   --exclude=origin/<run branch>... --remotes=origin`). The run's own branches are its release
   branch, the checkpoint branch this attempt persists to (`:execution/task-branch`) and the one a
   resume started from (`:resume-workspace`); a chained task's inherited parent branch still
   counts as published. Base commits are the task's recorded base refs, else on a first attempt
   the environment's base commit, so a local base branch ahead of origin is not this run's work. A
   rename is released as its two paths. No commit yet is nothing to release. A missing origin, a
   failed HEAD probe, or git failing to read a file is a failure, never an empty list; an
   interrupt propagates instead of becoming a retryable failure.
2. `release-unpushed-entries` (new namespace): each path's entry at HEAD (`git ls-tree`,
   `git cat-file`): a deletion when HEAD lacks it, a submodule's commit, else the file's content.
3. `release.clj`: with a clean worktree the skip probe calls it, through a host or capsule git
   runner, and releases what it returns. A failed scan is a `:release/git-status-failed` anomaly.
4. `release-publish`: a successful publish without a PR while `:create-pr?` is set fails with
   `:release/pr-missing`; an explicit `:create-pr? false` is honored.
5. `release-executor/interface` exports `release-branch-name`, so the probe excludes the run's own
   branch.

## Testing Plan

1. `release-releases-unpushed-commits-test` fails on the previous code (the probe skipped the
   committed files) and passes now.
2. `release_test.clj`: unpublished work is never skipped (including work on the run's release
   and checkpoint branches), commits from before the task's base are not released, a rename is
   released as both paths, an interrupted scan rethrows with the flag set, a failed HEAD probe
   fails the scan, a chained DAG task's commits already on its parent's branch are not re-released.
3. `release_publish_test.clj`: `publish-requires-the-pr-it-was-asked-for-test`,
   `create-pr-option-test`.
4. Skip tests give their worktree an origin with nothing ahead; the capsule skip-probe test answers
   the HEAD and origin probes.
5. Ran the phase-software-factory release, file-scan and publish tests and the project tests
   `handoff`, `artifact_persistence` and `gate_before_publish`.

## Deployment Plan

No configuration or data migration. Takes effect with the next CLI build.

## Related Issues/PRs

Follows #2253 (publish only after the gates pass), #2255 and #2256 (one release branch and PR per
run, lineage across attempts) and #2261 (push and PR creation as effect transactions).

## Notes

1. `release.clj` (6 layers) and `release_test.clj` (5) were already over SL003 on main; the
   commits that touch them used `MINIFORGE_STRATUM_BUDGET_MODE=warn`, adding no layer. The scan
   lives in new namespaces for that reason.
2. Not changed here: the `{:failure ...}` shape is still built by hand in a few places in
   `release.clj`, and the publish-release stub result is repeated in four test files.

## Checklist

- [x] Regression test fails on the previous code
- [x] Independent review and pinned-standards check
- [ ] Copilot threads answered and resolved, CI green, no conflicts
- [ ] Merged
