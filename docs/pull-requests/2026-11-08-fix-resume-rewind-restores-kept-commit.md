<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow): a local rewind resumes at the kept phase's commit

## Overview

`miniforge resume <id> --from-phase <p>` in local mode now resumes from the workspace of the last
phase it keeps. Before, it resumed from the latest phase's workspace. After the bundle restore, the
task branch is put at the kept checkpoint's `:workspace/commit-sha`, found with
`rev-parse --verify <sha>^{commit}`. When that commit does not resolve, or the branch cannot be
moved to it, the resume is refused instead of going on from the wrong commit.

## Motivation

1. `rewind-to-phase` in `resume.clj` picks the last `:workspace/persisted` checkpoint made by a
   kept phase. Its docstring says it never restores one made by `p` or later.
2. `restore-local-workspace-checkpoint!` passed only `:bundle-path` and `:branch` to
   `dag/restore-workspace!`. The checkpoint's `:commit-sha` was dropped.
3. `restore-from-bundle!` runs `git fetch <bundle> branch:branch`, which takes the bundle's tip.
4. `persist-workspace!` writes `<archive-dir>/<workflow-id>/<env-id>.bundle`, with no phase in the
   name. Every phase of a task overwrites it, so the tip is the latest persist.
5. `acquire-execution-environment!` then cut the worktree from that branch: the rewound phases'
   work was back in the worktree the re-run started from.

The task snapshot history design (miniforge-ai/miniforge#2463) depends on this. Its resume puts
the worktree at the kept phase's commit, then resets files to that phase's snapshot.

## Changes in Detail

1. New `checkpoint_branch.clj`, workflow: `pin-branch!` resolves `<sha>^{commit}` in the host
   repo and fails with `:checkpoint-commit-missing` when it does not resolve. A branch already at
   the commit is left alone. Otherwise it runs `git branch -f`, which refuses a branch another
   worktree has checked out; that refusal is returned. It shells out through the existing
   `dag-merge-git/run-git`.
2. `runner_environment.clj`, workflow:
   1. `restore-local-workspace-checkpoint!` restores the bundle as before, then calls
      `pin-checkpoint-commit!`. For a checkpoint that recorded its commit, that pins the branch,
      whether or not the bundle restore worked: the pin needs only the commit, and a failed
      restore still logs its warning. A checkpoint without a commit gets the restore's result, as
      before.
   2. When the restore throws, it now returns an error result instead of nil, so an attempted
      restore always has a result. Its warnings are unchanged.
   3. `restore-refusal` turns a failed pin into a `:conflict` anomaly with the new
      `:env/restore-refused` message, which carries git's error.
   4. In `acquire-execution-environment!`, that anomaly is the value of the `try`, and no worktree
      is acquired. After the `try` it is thrown as `:anomalies.executor/acquisition-failed`. The
      throw sits outside the `try` because local mode turns what it catches into a warning and a
      nil environment. Every other local acquisition failure still does that.
3. Two system-locale messages for `pin-branch!`'s errors.

## Behaviour change for a resume without `--from-phase`

Its checkpoint is the latest one, and that checkpoint's commit is normally the bundle's tip, so
nothing changes. It differs only when the host branch has moved past the last persisted commit,
for example through a commit an agent made itself after the last phase boundary:

1. a branch no worktree has checked out is moved back to the checkpoint's commit;
2. a branch a stale worktree still has checked out, at another commit, refuses the resume.

Before, the first case failed the fetch as non-fast-forward, warned, and cut the worktree from the
moved branch. The second case was accepted and cut from the checked-out branch.

A failed bundle restore (for example a deleted bundle) no longer decides the outcome for a
checkpoint with a commit: if the host repo holds the commit the resume goes on at it, and
otherwise it is refused. Before, it warned and resumed from whatever the branch held.

## Testing Plan

1. New `resume_restore_test.clj`, workflow, real git through the isolation fixture:
   1. two phases persist through `persist-workspace-at-phase-boundary!` to one bundle; a resume
      from the first phase's checkpoint puts the worktree HEAD and the task branch at the first
      phase's commit, for an in-place resume and for a new attempt;
   2. a branch a live worktree has checked out at the checkpoint's commit restores;
   3. the resume is refused when that branch would have to move back, and when the commit is
      not in the host repo;
   4. a missing bundle does not stop a resume whose commit the host repo holds.
2. On `main`'s `runner_environment.clj` the new test fails 9 of 15 assertions: the worktree HEAD
   and the branch are at the second phase's commit, the second phase's file is present, nothing
   is refused, and the missing-bundle resume also starts at the second phase's commit.
3. This branch: `resume-restore-test`, `runner-environment-test`,
   `environment-notifications-test` and `runner-test`, 44 tests, 149 assertions, 0 failures.

## Notes

1. The pin lives in the workflow brick, not in the worktree executor's restore. Both
   `protocols/impl/worktree.clj` and the dag-executor `interface.clj` are over the 3-layer budget
   (SL003) on `main`, so a commit that touches either fails the stratum-lint hook. Splitting them
   is a separate change.
2. The existing `catch Exception` sites stay plain `try`; rule 211 leaves them as they are. The
   refusal is never thrown inside them, so no catch reads `:anomaly/category` from `ex-data`.
3. `acquire-execution-environment!`'s `try` body is re-indented by binding it in the `let`. Its
   contents are unchanged apart from the `or` that stops at a refusal.
4. After a rewind the rewound phases' commits are no longer on the task branch. They stay in the
   original run's bundle; an in-place resume overwrites that bundle at its first persist, a new
   attempt writes its own.
5. The new test runs real git, like `runner_environment_test.clj` and
   `merge_parent_branches_integration_test.clj` beside it, and uses the same isolation fixture.

## Deployment Plan

No configuration or data migration. Checkpoints already on disk carry `:workspace/commit-sha`.

## Related Issues/PRs

1. miniforge-ai/miniforge#2463: task snapshot history design, whose resume step needs this.

## Checklist

1. [x] Regression test fails on the previous code
2. [x] Adversarial review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
