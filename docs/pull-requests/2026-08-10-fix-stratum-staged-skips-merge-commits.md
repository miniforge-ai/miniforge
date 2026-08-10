<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: skip the stratum-lint gate on merge commits

## Overview

`lint/stratum-staged` (the `lint:stratum` pre-commit gate) now detects a
merge in progress and skips, printing one line saying so. Same exemption
`bb commit-budget` gained on 2026-08-07, sharing its
`commit-budget/merge-in-progress?` detector.

## Motivation

The gate lints every staged `.clj`/`.cljc` file. On a merge commit the
staged set includes every file arriving from the merged-in branch —
files the merging author did not touch, already stratum-linted on the
PRs that introduced them.

Concrete failure on 2026-08-10: merging `origin/main` into a feature
branch staged
`components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj`,
which is mid-way through its own 6-PR namespace split on main (#1729 is
1/6) and legitimately still SL003 there. The gate blocked the merge and
required `MINIFORGE_STRATUM_BUDGET_MODE=warn` to proceed — training the
same override reflex the commit-budget fix was written to prevent.

Skipping entirely (rather than restricting to files differing from both
parents) matches the commit-budget precedent: a merge is not the merging
author's change, and a partial lint of a merge would still autofix and
re-stage files mid-way through their own split waves.

## Layer

Development-tool tasks (`tasks/lint.clj`, `tasks/stratum.clj` docstring),
a new test namespace, the smoke-test registry, and the operator-facing
description in `agents.md`.

## Changes in Detail

- `lint/stratum-staged`: returns on a merge in progress *before*
  listing staged files, so no incoming file is read, autofixed, or
  re-staged. Detection is `commit-budget/merge-in-progress?` — one
  canonical detector rather than a copy; it already handles the
  linked-worktree case (`git rev-parse -q --verify MERGE_HEAD`, not a
  `.git/MERGE_HEAD` path stat) and is pinned e2e by
  `commit-budget-test` against a real linked-worktree merge.
- `stratum/budget-mode-env` docstring: records that merge commits never
  reach the post-fix budget check, so warn mode is not needed for them.
- `development/test/lint_test.clj` (new): pins the skip and the
  non-merge dispatch. Requires the task namespaces directly (`tasks` is
  on the `:test` classpath) instead of `load-file`, which cannot
  resolve `lint`'s sibling-namespace requires.
- `resources/precommit-smoke-tests.edn`: adds `lint-test` to the smoke
  set. Pure `with-redefs` — no subprocess, no filesystem.
- `agents.md`: extends the 2026-08-07 merge-exemption entry to cover
  the stratum gate.

## Standards Audit

- Stratified design (001/210): `stratum-staged` stays at stratum 2; the
  skip adds no in-file dependency (`commit-budget` is an external
  namespace). Test file carries its own Layer headings, helpers at 0,
  tests at 1.
- One canonical location per datum: merge detection stays in
  `commit-budget`; `lint` requires it rather than duplicating the
  subprocess call.
- Testing (400): the merge-state fixture already exists in
  `commit-budget-test`; this namespace deliberately does not build a
  second scratch repo to re-pin the same detector.

## Testing Plan

- `lint-test` + `commit-budget-test` — 11 tests, 43 assertions, green
  under the smoke runner's own aliases (`clojure -M:test:dev`).
- Mutation-checked rather than trusting green:
  - Replacing the merge check with `false` (skip lost) errors
    `merge-skips-stratum-lint-test` — its `staged-by-ext` stub throws
    if the gate lists files mid-merge.
  - Replacing it with `true` (gate always skips) fails
    `non-merge-commit-still-stratum-linted-test` on all three
    assertions.
- `bb lint:stratum` in this worktree (no merge): normal path, green —
  the `commit-budget` require also loads under babashka, not just the
  JVM test runner.
- End-to-end both directions in a detached scratch worktree of this
  repo with a real `--no-commit` merge in progress (54 staged `.clj`
  files): the pre-fix gate reproduces the original failure verbatim
  (SL003 on `mdc_compiler.clj`, exit 1); with the fixed task files
  copied in, the gate prints the skip line and exits 0. Worktree
  removed afterwards.
