<!--
  Title: Split bases/cli worktree.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split top-level worktree.clj (rule 210)

## Overview

Splits the `.git`-marker directory walk out of
`ai.miniforge.cli.worktree` (`bases/cli/src/ai/miniforge/cli/worktree.clj`)
into its own sibling namespace,
`ai.miniforge.cli.worktree.root-resolution`, resolving a stratum-lint
SL003 finding (the combined namespace measured 5 real layers, over the
rule 210 budget of 3).

Note: this is the top-level `bases/cli/src/ai/miniforge/cli/worktree.clj`,
distinct from `bases/cli/src/ai/miniforge/cli/main/commands/worktree.clj`
(a separate namespace being split concurrently in another PR).

## Motivation

Part of the stratum-lint rule-210 remediation program's `bases/cli`
batch. `worktree.clj` mixed two thematic groups under one 5-layer
stack: the directory walk down to the nearest `.git` marker
(`git-marker-name`, `file->dir`, `canonical-dir`, `git-marker-path`,
`nearest-git-root`) and the two entry points built on top of it
(`worktree-root`, `git-info`).

## Changes in Detail

- New file `worktree/root_resolution.clj`
  (`ai.miniforge.cli.worktree.root-resolution`): the marker-walk group
  — `git-marker-name`, `file->dir` (layer 0), `canonical-dir`,
  `git-marker-path` (layer 1), `nearest-git-root` (layer 2). 3 layers,
  unchanged behavior. `nearest-git-root` remains public; `canonical-dir` is now public so `worktree-root` in the parent namespace can call it across namespaces.
- `worktree.clj`: now `worktree-root` (layer 0, calls
  `root-resolution/nearest-git-root` and
  `root-resolution/canonical-dir`) and `git-info` (layer 1, calls
  `worktree-root` in the same file). 2 layers. Both keep their
  original names, arities, and docstrings at the original
  `ai.miniforge.cli.worktree` namespace — no public API changed.

This is pure code motion: no logic changed, only the marker-walk
helpers relocated and re-namespaced.

## Fan-in

`ai.miniforge.cli.worktree` (fully-qualified, distinct from
`ai.miniforge.cli.worktree.main.commands.worktree`) is required by:

- `bases/cli/src/ai/miniforge/cli/workflow_runner.clj`
- `bases/cli/src/ai/miniforge/cli/workflow_runner/context.clj`
- `bases/cli/src/ai/miniforge/cli/workflow_runner/context_git.clj`
- `bases/cli/src/ai/miniforge/cli/main/commands/workflow_commands.clj`
- `bases/cli/src/ai/miniforge/cli/main/commands/worktree.clj`
- `bases/cli/test/ai/miniforge/cli/worktree_test.clj`
- `bases/cli/test/ai/miniforge/cli/workflow_runner/context_test.clj`
- `bases/cli/test/ai/miniforge/cli/main/commands/workflow_commands_test.clj`

All of these call only `worktree/worktree-root`, which stays defined
at the same namespace/name/arity, so none needed source changes.

## Testing Plan

- `stratum-lint` clean on both resulting files (exit 0, was SL003 exit
  1 on the original).
- Direct `clojure -M:dev:test` `run-tests` (not `bb test`, per program
  lessons) on every affected namespace:
  - `ai.miniforge.cli.worktree-test` — 2 tests, 2 assertions, 0
    failures/errors.
  - `ai.miniforge.cli.workflow-runner.context-test` — 2 tests, 5
    assertions, 0 failures/errors.
  - `ai.miniforge.cli.main.commands.workflow-commands-test` — 14
    tests, 28 assertions, 0 failures/errors.
- Compile-checked every fan-in caller namespace directly
  (`workflow-runner`, `workflow-runner.context`,
  `workflow-runner.context-git`, `main.commands.workflow-commands`,
  `main.commands.worktree`) — all load cleanly.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 program, `bases/cli` batch (see
  the `policy-pack/builtin_detectors.clj` split, miniforge#1781/#1730,
  for the established single-sibling-file convention this follows).

## Checklist

- [x] stratum-lint clean on both resulting files
- [x] Direct `run-tests` green on every affected namespace
- [x] Adversarial self-review: def set unchanged, only relocated;
      public API (`worktree-root`, `git-info`) unchanged
- [x] Fan-in confirmed repo-wide (components, bases, projects) before
      starting; every caller re-verified to compile after the split
