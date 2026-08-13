<!--
  Title: Split cli/workflow_selector.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split workflow_selector.clj (rule 210)

## Overview

Splits spec-feature-extraction and rule-matching out of
`ai.miniforge.cli.workflow-selector` into two new sibling namespaces,
`ai.miniforge.cli.workflow-selector.spec-analysis` and
`ai.miniforge.cli.workflow-selector.rules`, resolving a stratum-lint
SL003 finding (the combined namespace measured 5 real layers, over
the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program, `bases/cli`
batch. `workflow_selector.clj` (274 lines, one caller repo-wide — its
own test namespace) is one of this batch.

## Changes in Detail

- New file `workflow_selector/spec_analysis.clj`: the extraction
  primitives (`extract-type`, `extract-implementation-plan`,
  `count-prs`, `has-dependencies?`, `extract-description-keywords`,
  `extract-constraints-mentions`), `estimate-size`, and their
  composition `analyze-spec` — 3 layers.
- New file `workflow_selector/rules.clj`: `selection-result`, the six
  individual rule matchers (`match-multi-phase-rule` ...
  `match-unknown-rule`), and the ordered `selection-rules` list — 3
  layers. `match-rule` itself does **not** live here: dispatching over
  `selection-rules` is a 4th real layer on top of these 3 (confirmed
  by `stratum-lint --fix`, which relabeled it `^{:stratum 3}` and
  reported the file back over budget after an initial attempt at
  keeping it in this file).
- `workflow_selector.clj` (parent): `explain-selection`, an
  `analyze-spec` re-export (`def` pointing at
  `spec-analysis/analyze-spec`), and `match-rule` (real
  implementation — `(some (fn [rule-fn] (rule-fn features))
  rules/selection-rules)`) at layer 0, plus `select-workflow` at
  layer 1. `match-rule` stays here rather than in
  `rules.clj` because the cross-namespace call to
  `rules/selection-rules` doesn't add to *this* file's own layer
  depth — the same technique `loader.clj`'s rule-210 split used
  (miniforge#1772).
- Kept as the single public entry point (rather than moving
  `analyze-spec`/`match-rule` calls to `ws/spec-analysis`,
  `ws/rules` at every call site) so the existing test namespace
  (`bases/cli/test/.../workflow_selector_test.clj`, which calls
  `ws/explain-selection`, `ws/analyze-spec`, `ws/match-rule`, and
  `ws/select-workflow`) needed no changes.

This is pure code motion — no behavior or detection-logic changes.

## Testing Plan

- `stratum-lint` clean on all three touched/new source files (`bb -m
  stratum-lint.interface` exit 0; `--fix` made no further changes,
  confirming the declared `^{:stratum N}` annotations match the real
  computed layer depths).
- `clj-kondo` clean (0 errors, 0 warnings).
- Direct namespace verification (not just pre-commit smoke, per
  program lesson to distrust `bb test` alone under load):
  `clojure -M:dev:test -e "(require 'ai.miniforge.cli.workflow-
  selector) (require 'ai.miniforge.cli.workflow-selector-test)
  (clojure.test/run-tests 'ai.miniforge.cli.workflow-selector-test)"`
  — 6 tests, 75 assertions, 0 failures/errors.
- Full pre-commit gate (345 tests / 1301 assertions on the
  change-scope smoke suite, plus 8 GraalVM/Babashka compatibility
  tests) passed on the final wire-up commit.
- Repo-wide grep for the fully-qualified namespace
  (`ai\.miniforge\.cli\.workflow-selector\b`) across `components/`,
  `bases/`, and `projects/` found exactly one caller —
  `bases/cli/test/ai/miniforge/cli/workflow_selector_test.clj` — which
  needed no changes since the parent namespace's public API
  (`explain-selection`, `analyze-spec`, `match-rule`, `select-workflow`)
  is unchanged.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.
`workflow_selection_config.clj` (a separate SL003 finding in the same
directory) was being split concurrently by a sibling task — this PR
does not touch it, though `workflow_selector.clj` requires it (via
`rules.clj` now) and that require path was double-checked to still
resolve correctly.

## Related Issues/PRs

- Part of the stratum-lint rule-210 remediation program, `bases/cli`
  batch (see miniforge#1772 `loader.clj` and miniforge#1731
  `knowledge_safety.clj` for the established convention this follows,
  including the "cross-namespace calls don't count toward local layer
  depth" technique used to keep `match-rule` in the parent file).

## Checklist

- [x] stratum-lint clean on all resulting files (plain check and
      `--fix` agree, exit 0)
- [x] Direct `clojure -M:dev:test` run green on the affected test
      namespace (not just pre-commit smoke)
- [x] Adversarial self-review: public API of the parent namespace
      unchanged (all four functions the test namespace calls still
      resolve there)
- [x] Zero fan-in confirmed repo-wide (components/bases/projects)
      before starting; the one caller found needed no changes
- [x] Pure code motion — no detection/selection logic changed
