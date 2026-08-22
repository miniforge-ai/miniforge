<!--
  Title: Split cli/main/commands/etl.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split etl.clj (rule 210)

## Overview

Splits `ai.miniforge.cli.main.commands.etl` into three sibling
namespaces, resolving a stratum-lint SL003 finding (the combined
namespace measured 4 real layers, over the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program's `bases/cli`
batch: 13 `main/commands/*.clj` files being split concurrently as
independent command handlers.

## Changes in Detail

- New file `commands/etl/paths.clj`
  (`ai.miniforge.cli.main.commands.etl.paths`): pack-path resolution
  shared by `etl run` and `etl validate` — `single-file-under`,
  `resolve-env-path`, `resolve-pipeline-path` (all stay private),
  `resolve-pack-paths` (made public, see below) — 3 layers.
- New file `commands/etl/repo.clj`
  (`ai.miniforge.cli.main.commands.etl.repo`): `etl repo` support —
  `validate-git-url` (already public), `git-clone-temp` (stays
  private), `analyze-repo-url!` (made public, see below) — 2 layers.
- New file `commands/etl/shell.clj`
  (`ai.miniforge.cli.main.commands.etl.shell`): JVM shell-out shared
  by `run`/`list`/`validate`/`registry` — `find-miniforge-root` (stays
  private), `shell-etl!` (made public, see below) — 2 layers.
- `etl.clj`: now keeps only the five public command entry points
  (`etl-repo-cmd`, `etl-list-cmd`, `etl-registry-cmd`, `etl-run-cmd`,
  `etl-validate-cmd`), delegating to the three namespaces above. None
  of the five call each other — each calls straight into a sibling
  namespace, and those cross-namespace hops no longer count toward
  this file's own layer depth — so all five now measure a single
  layer (down from 4).
- Visibility changes required by the new cross-namespace call sites
  (pure code motion otherwise, same pattern as PR #1772's
  `defn-`→`defn` precedent):
  - `etl.repo/analyze-repo-url!`: `defn-` → `defn` (called from
    `etl-repo-cmd`)
  - `etl.shell/shell-etl!`: `defn-` → `defn` (called from 4 of the 5
    commands)
  - `etl.paths/resolve-pack-paths`: `defn-` → `defn` (called from
    `etl-run-cmd`, `etl-validate-cmd`)
- `etl_test.clj`: `validate-git-url-test` now calls
  `etl-repo/validate-git-url`; the three `with-redefs-fn` targets on
  `git-clone-temp` now reference `#'etl-repo/git-clone-temp` (moved to
  `etl.repo`, stays private there — var-quote cross-namespace access
  works regardless of privacy, the same mechanism the original tests
  already relied on).
- `etl_anomaly_test.clj`: `resolve-pipeline-path` and
  `resolve-env-path` moved to `etl.paths` (stay private there);
  var-quote targets updated to `#'etl-paths/resolve-pipeline-path` and
  `#'etl-paths/resolve-env-path`.

This is pure code motion aside from the three required visibility
changes and the test call-site updates above — no behavior changed.

## Testing Plan

- `stratum-lint` clean on all four touched/added source files (exit
  0, was SL003 exit 1 on the original `etl.clj`).
- `clj-kondo` clean on all six touched files (0 errors, 0 warnings).
- `bb pre-commit` green on all three commits (commit-budget, poly
  check, lint, stratum-lint, smoke tests, GraalVM compatibility).
- Repo-wide grep for the fully-qualified namespace
  (`ai\.miniforge\.cli\.main\.commands\.etl\b`, not a symbol-prefix
  guess) across `components/`, `bases/`, and `projects/` found exactly
  two callers: `bases/cli/src/ai/miniforge/cli/main.clj` (calls only
  the five public command fns, which stayed in `etl.clj` — no update
  needed) and the two test files (updated above). No caller under
  `projects/miniforge/test/`.
- Both touched test namespaces verified directly (not just via `bb
  pre-commit`'s smoke subset, which doesn't include them):
  `clojure -M:dev:test -e "(require 'ai.miniforge.cli.main.commands.etl-test)
  (clojure.test/run-tests 'ai.miniforge.cli.main.commands.etl-test)"` →
  6 tests, 11 assertions, 0 failures/errors. Same for
  `ai.miniforge.cli.anomaly.etl-anomaly-test` → 7 tests, 21 assertions,
  0 failures/errors.
- `ai.miniforge.cli.main` (the caller namespace) required directly to
  confirm it still compiles against the new `etl.clj` shape — clean.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file. The
other 12 `main/commands/*.clj` files in this batch are tracked
separately (concurrent, independent PRs).

## Related Issues/PRs

- Part of the stratum-lint rule-210 program's `bases/cli` batch (task
  #38). Follows the extraction convention established by `loader.clj`
  (miniforge#1772) and `knowledge_safety.clj`
  (2026-08-09-refactor-split-policy-pack-knowledge-safety.md).

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] `bb pre-commit` green (commit-budget, poly:check, lint,
      stratum-lint, smoke tests, GraalVM compatibility)
- [x] Adversarial self-review: def set unchanged except the three
      required `defn-`→`defn` visibility flips, documented above
- [x] Test call sites updated for both white-box test files
- [x] Zero fan-in surprises: repo-wide grep confirmed only the two
      known callers (`main.clj`, the two `etl` test files)
