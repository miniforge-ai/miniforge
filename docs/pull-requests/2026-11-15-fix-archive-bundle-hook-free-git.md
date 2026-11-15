<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: archive-bundle! runs git with hooks and fsmonitor off

## Overview

`archive-bundle!` now runs `git status`, `git add -A`, `git commit`, and
`git checkout -B` with `-c core.hooksPath=/dev/null -c core.fsmonitor=false`.
No hook or fsmonitor program named in the shared config runs during a
persist.

## Motivation

A local-mode task worktree is a linked worktree, so it reads the host
checkout's `.git/config`. The persist commit already passed `--no-verify`,
but that skips only the `pre-commit` and `commit-msg` hooks. Checked with
git 2.48.1, in a linked worktree whose shared config sets `core.hooksPath`
and `core.fsmonitor`:

1. `git commit --no-verify` still runs `prepare-commit-msg`, `post-commit`,
   and `reference-transaction`.
2. `git checkout -B` runs `post-checkout` and `reference-transaction`.
3. `git status`, `git add`, `git commit`, and `git checkout -B` each start
   the `core.fsmonitor` program.

With the two `-c` options, none of them ran and the commit succeeded.

The hooks path may be the operator's own, or one a task wrote into the
shared config. Either way a persist is infrastructure, not a commit anyone
reviews, and nothing in it should run code from config.

## Changes in Detail

1. `worktree/hook-free-git-args`, in the git plumbing namespace: builds
   `-C <worktree>` plus the two `-c` options ahead of the subcommand's args.
   It refers to no other def in its file, so it sits in Layer 0.
2. In `worktree.archive-steps`, `dirty?`, `stage-all!`, `commit-staged!`,
   and `ensure-named-branch!` pass their args through it. These are the
   persist calls that touch the index or a ref. `rev-parse`, `rev-list`,
   and `bundle create` run no hook and no fsmonitor, so they are unchanged.
   The helper lives in another namespace, so every step keeps its stratum.
3. `commit-staged!` keeps `--no-verify` and `-c commit.gpgsign=false`,
   which stays last before `commit`.
4. Docstrings of `commit-staged!` and `archive-bundle!` updated.

## Testing Plan

1. `archive-bundle-runs-no-hook-or-fsmonitor-from-shared-config-test`
   (real git): a detached linked worktree whose shared config sets
   `core.hooksPath` to recording hooks and `core.fsmonitor` to a recording
   program. `archive-bundle!` persists the task's file, and nothing is
   recorded. Without the change in `archive-steps` the test fails, having
   recorded the fsmonitor program five times, `post-checkout`,
   `post-commit`, `prepare-commit-msg`, and `reference-transaction`
   fifteen times.
2. `persist-commit-disables-signing-test`: also asserts the hook-free
   options come before `commit`.
3. `worktree-test`: the `run-git` mocks dispatched on the args after
   `-C <path>`. They now use `worktree-git-mock/subcommand`, which also
   drops leading `-c <name>=<value>` options, so they still match `status`
   and `checkout`. No assertion changed. The helper has its own test
   namespace because stratum-lint would move every Layer 0 test that
   referred to a same-file helper up a layer.
4. Worktree test namespaces: 64 tests, 169 assertions, 0 failures.

## Deployment Plan

None.

## Known gaps

1. Other `run-git` calls in the worktree executor still run under the
   shared config, for example `git worktree add`, which runs
   `post-checkout`. This PR covers the persist path only.
2. `filter.<driver>.clean` still runs on `git add` for a path whose
   `.gitattributes` names that driver. It cannot be turned off without
   knowing the driver name.

## Related Issues/PRs

Base branch: `refactor/split-worktree-executor` (#2526), which this
change needs: on `main`, `worktree.clj` is over the stratum budget and no
commit to it passes the hook. Retarget to `main` once #2525 and #2526
merge.

1. Follow-up 3 of the task snapshot history design (#2463,
   `docs/design/task-snapshot-history.md`).
2. Companion PR #2476 (merged): the host-git guard watches config that
   runs commands.
3. Step 3 of 3, after #2525 (stratum annotation) and #2526 (the split).

## Checklist

- [x] Hook-free options on the persist calls that touch the index or a ref
- [x] Real-git regression test
- [x] Existing mocks updated
