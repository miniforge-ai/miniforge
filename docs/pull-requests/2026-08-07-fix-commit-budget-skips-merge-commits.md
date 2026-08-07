<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: skip the commit budget on merge commits

## Overview

`bb commit-budget` now detects a merge in progress and skips the check,
printing one line saying so.

## Motivation

The gate counts the full staged diff. On a merge commit that diff includes
every line arriving from the merged-in branch — content the merging author
did not write.

On 2026-08-07, merging `github/main` into a feature branch to pick up an
unrelated squash-merged PR produced a 491-reportable-line failure, all of it
`components/execution-grant/**`: another author's work, already reviewed and
merged on its own PR. The merge itself contributed no new code.

A merge commit's size is not a reviewability signal about the merging
author's change. Forcing `MINIFORGE_COMMIT_BUDGET_OVERRIDE` on routine
merges trains people to reach for the override and dilutes its signal for
the cases it exists for.

## Layer

Development-tool task (`tasks/commit_budget.clj`), plus its test and the
operator-facing description in `agents.md`.

## Changes in Detail

- `commit-budget/merge-in-progress?`: asks `git rev-parse -q --verify
  MERGE_HEAD` rather than stat-ing `.git/MERGE_HEAD`. In a linked worktree
  `.git` is a file pointing at `…/.git/worktrees/<name>/`, where the merge
  state actually lives, so the path check finds nothing — and nearly all
  work in this repo happens in worktrees. Takes no arguments: it answers
  for whatever repository git itself would act on, which under the hook is
  the exported `GIT_DIR` and is the same repository `staged-diff` reads.
- `check-commit-budget!`: a merge in progress is the first branch of the
  decision `cond`, ahead of the override and size branches, so no override
  is needed and none is consumed.
- `agents.md`: records the exemption and that `pr-budget` is unaffected.
- `resources/precommit-smoke-tests.edn`: the entry's comment claimed "no
  subprocess"; the merge test spawns git, so the comment now says so.

## `pr-budget` — checked, not affected

`pr-budget` diffs `merge-base(base, head)..head` (three-dot). When a branch
merges the base in, the base tip becomes an ancestor of head, so the
merge-base moves up to it and the incoming content falls out of the diff.

Confirmed against both merge-containing PRs in recent history rather than
assumed:

- PR #1697 — head is itself `Merge remote-tracking branch 'github/main'`.
  `base.sha` equals the merge commit's second parent; the three-dot diff is
  10 files, the branch's own.
- PR #1693 — five merge commits, two of them from `github/main`. Three-dot
  diff is 7 files.

The failure mode would be a `base.sha` older than what the branch merged in;
in both cases `base.sha` equals the merge-base, so GitHub is not reporting a
stale base here. Reproduced both directions in a scratch repo: with the
current base tip the merged-in file is absent from the diff, with a
deliberately stale base it appears.

## Standards Audit

- Stratified design (001/210): `merge-in-progress?` has no in-namespace
  dependencies and sits at stratum 0 beside `override-rationale`, which is
  likewise external-state-reading with no in-namespace deps.
- Testing (400): magic numbers extracted to named fixtures at Layer 0;
  the git-spawning scenario is the only filesystem test and is isolated in
  its own Layer 2 section.
- Exception handling (211) does not apply by glob (`components|bases|
  projects/**/src`); the single `ex-info` throw is a test-fixture failure
  boundary.

## Testing Plan

- `commit-budget-test` — 9 tests, 38 assertions, green.
- Mutation-checked both new tests rather than trusting green:
  - Replacing `git rev-parse` with a `.git/MERGE_HEAD` stat fails
    `merge-detected-in-linked-worktree-test`.
  - Deleting the merge branch from the `cond` fails
    `merge-skips-the-budget-test`.
  - Both fail as named assertions and the suite still completes. An earlier
    draft passed `default-budget` to the second test, which made the
    regression exit the test JVM mid-run and take every later namespace's
    result with it; it now passes a budget the fixture cannot exceed.
- Run under a simulated hook environment (`GIT_DIR` and `GIT_INDEX_FILE`
  exported) as well as a clean one. The first draft passed clean and failed
  under the hook: the fixture's git commands inherited `GIT_DIR` and aimed
  at the real repository regardless of their `:dir`. `git add -A` failed
  with "this operation must be run in a work tree" and `git!`'s
  throw-on-non-zero surfaced it; a command that had not failed would have
  been operating on the live checkout. The fixture now runs with every
  `GIT_*` variable stripped from the environment. This is also why
  `merge-in-progress?` gained no dir-taking arity — an ambient `GIT_DIR`
  outranks a subprocess `:dir`, so such an arity would have answered about
  the wrong repository under the hook, which is the only place the gate
  runs. The test drives the zero-arity form in its own process instead.
- End-to-end: `bb commit-budget` run inside a real merge in this worktree.
