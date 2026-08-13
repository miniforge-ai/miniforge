<!--
  Title: Split cli/workflow_selection_config.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split workflow_selection_config.clj (rule 210)

## Overview

Splits `ai.miniforge.cli.workflow-selection-config` into two sibling
namespaces by concern, resolving a stratum-lint SL003 finding (the
combined namespace measured 4 real layers, over the rule 210 budget of
3).

## Motivation

Part of the stratum-lint rule-210 remediation program, `bases/cli`
batch. `workflow_selection_config.clj` mixed two independent
concerns that only meet at the top-level `resolve-selection-profile`
call: (1) reading/merging the configured-profile mapping off the
classpath, and (2) generic fallback scoring of workflows by
characteristics when no configured mapping applies. Neither concern
depends on the other, so each splits cleanly into its own file.

## Changes in Detail

- New file `workflow_selection_config/profiles_resource.clj`
  (namespace `ai.miniforge.cli.workflow-selection-config.profiles-resource`):
  the classpath resource loading/merging (`selection-profiles-resource`,
  `read-selection-profile-config`, `configured-selection-profiles`) — 2
  layers, unchanged behavior.
- New file `workflow_selection_config/fallback.clj` (namespace
  `ai.miniforge.cli.workflow-selection-config.fallback`): generic
  fallback scoring (`workflow-characteristics`,
  `available-workflow-definitions`, `simplest-workflow-id`,
  `most-comprehensive-workflow-id`, `resolve-profile-fallback`) — 3
  layers, unchanged behavior. `available-workflow-definitions` and
  `resolve-profile-fallback` were `defn-` in the original file; both
  are now public since the parent namespace calls them across the
  namespace boundary.
- `workflow_selection_config.clj`: now only the public
  `resolve-selection-profile` API (1 layer), delegating to the two
  sibling namespaces.
- `workflow_selection_config_test.clj`: updated to require
  `profiles-resource` directly for the
  `configured-selection-profiles-test` assertion (the function moved
  out of the parent namespace). `resolve-selection-profile-test` is
  unchanged — it exercises the public API, which stayed in place.

This is pure code motion: no logic changed, only relocated and
re-namespaced. `resolve-selection-profile` (the only symbol every
other caller in the repo uses) kept its original namespace and
signature, so `workflow_recommender.clj`,
`main/commands/resume.clj`, `main/commands/resume_test.clj`, and
`workflow_selector.clj` needed no changes.

## Testing Plan

- `stratum-lint` clean on all three resulting files (exit 0, was SL003
  exit 1 on the original).
- Full `bb pre-commit` suite green (345 tests / 1301 assertions, plus
  the separate GraalVM/Babashka compatibility pass) at commit time.
- Direct namespace-level verification (not just `bb test`
  change-scope) on the target test and every real caller found by a
  repo-wide fully-qualified-namespace grep:
  - `ai.miniforge.cli.workflow-selection-config-test` — 2 tests / 6
    assertions, 0 failures.
  - `ai.miniforge.cli.main.commands.resume-test` — 9 tests / 40
    assertions, 0 failures.
  - `ai.miniforge.cli.workflow-recommender-test` — 2 tests / 11
    assertions, 0 failures.
  - `ai.miniforge.cli.workflow-selector-test` — verified directly
    (see PR checklist/CI for result — `workflow_selector.clj` is a
    concurrently-in-flight rule-210 split of its own; requires
    between the two files were checked and remain correct).

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 remediation program, `bases/cli`
  batch (see the `policy-pack/builtin_detectors.clj` split,
  miniforge#1730, for the established split convention this follows).

## Checklist

- [x] stratum-lint clean on all three resulting files
- [x] `bb pre-commit` green at commit time (345 tests / 1301
      assertions)
- [x] Direct `clojure -M:dev:test` verification on the target
      namespace and every real caller (not `bb test` change-scope
      alone)
- [x] Adversarial self-review: def set unchanged except two fns made
      public for cross-namespace calls, only relocated
- [x] Fan-in confirmed repo-wide before starting (fully-qualified
      namespace grep, not symbol-prefix guess)
