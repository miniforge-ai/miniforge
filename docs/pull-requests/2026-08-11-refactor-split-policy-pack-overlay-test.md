<!--
  Title: Split policy-pack/overlay_test.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split overlay_test.clj (rule 210)

## Overview

Splits `ai.miniforge.policy-pack.overlay-test` (206 lines, 4 real
layers) into four sibling namespaces under a new `overlay_test/`
subdirectory, resolving a stratum-lint SL003 finding (max 3 layers per
file). Pure test reorganization — every `deftest` and every assertion
is unchanged, only relocated and re-namespaced.

## Motivation

Part of the stratum-lint rule-210 remediation program's policy-pack
Wave 2 batch. `overlay_test.clj` is a test file with zero fan-in
repo-wide (confirmed via a fully-qualified namespace grep, not a
symbol-prefix guess — a prefix guess misses `:as`-aliased callers and
broke two earlier PRs in this batch on source files), so the split
carries no call-site risk.

## Changes in Detail

- New file `overlay_test/fixtures.clj`
  (`ai.miniforge.policy-pack.overlay-test.fixtures`): the shared test
  data every sibling test file needs — `make-rule` (layer 0),
  `make-pack` + the three named rule fixtures (layer 1), and the
  `base-pack`/`extra-pack` instances (layer 2). 3 layers, unchanged
  behavior.
- New file `overlay_test/enabled_filtering_test.clj`
  (`...overlay-test.enabled-filtering-test`): the one test that
  exercises `applicability/filter-applicable-rules` rather than
  `loader/resolve-overlay` — needs only `fixtures/make-rule`, not the
  pack fixtures. 1 layer.
- New file `overlay_test/resolution_test.clj`
  (`...overlay-test.resolution-test`): the core `resolve-overlay`
  mechanics — missing-base lookup failure, single/multiple
  `:pack/extends` inheritance, own-rule append order, and rule-ID
  collision detection. 1 layer.
- New file `overlay_test/overrides_and_taxonomy_test.clj`
  (`...overlay-test.overrides-and-taxonomy-test`): override
  application (severity, enabled?) and taxonomy-ref
  inheritance/conflict detection. 1 layer.
- Deleted `overlay_test.clj`: superseded by the four files above.

Every deftest now depends only on the required `fixtures` namespace,
not on same-file layered helpers, so each resulting test file sits at
a single stratum — well under the 3-layer budget.

This is pure code motion: no assertion changed, only relocated and
re-namespaced. Landed as two commits (add the split files, then remove
the original) to keep each commit under the 200-line commit budget —
the combined diff (add + delete) measures 300 reportable lines, over
the per-commit ceiling though under the 600-line PR ceiling.

## Testing Plan

- `stratum-lint` clean on all four new files (exit 0; the original
  file's SL003 finding is gone with its deletion).
- Manual `clojure.test/run-tests` across the three test namespaces:
  10 tests, 24 assertions, 0 failures/errors — identical to the
  pre-split baseline (`ai.miniforge.policy-pack.overlay-test` alone
  ran the same 10 tests / 24 assertions).
- Both commits went through the full `bb pre-commit` hook chain
  (commit-budget, `poly check`, `lint:clj`, `lint:stratum`, `fmt:md`,
  `test:precommit`, `test:graalvm`) clean — 345 tests/1301 assertions
  (precommit smoke) and 8 tests/623 assertions (GraalVM compat), 0
  failures/errors on each commit.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.
Remaining `policy-pack` files over budget are tracked separately as
part of the ongoing Wave 2 sweep.

## Related Issues/PRs

- Part of the stratum-lint rule-210 policy-pack Wave 2 batch (see
  `builtin_detectors.clj` #1730 for the small-file source-split
  reference, and the `workflow_runner.clj` splits #1662-#1667 for the
  original convention this follows, adapted here for a test file per
  the "sibling test files under a subdirectory" pattern).

## Checklist

- [x] stratum-lint clean on all four resulting files
- [x] Test/assertion count unchanged (10 tests / 24 assertions, before and after)
- [x] `bb pre-commit` green on both commits (full hook chain)
- [x] Adversarial self-review: deftest and fixture set unchanged, only relocated
- [x] Zero fan-in confirmed repo-wide (fully-qualified namespace grep) before starting
- [x] Commit budget: split into two commits (160 / 200, then 140 / 200 reportable lines)
