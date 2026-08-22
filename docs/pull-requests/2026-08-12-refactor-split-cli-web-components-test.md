<!--
  Title: Split cli/web/components_test.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split components_test.clj (rule 210)

## Overview

Splits `ai.miniforge.cli.web.components-test` (86 lines, 4 real layers)
into four sibling namespaces under a new `components_test/` subdirectory,
resolving a stratum-lint SL003 finding (max 3 layers per file). Pure test
reorganization — every `deftest` and every assertion is unchanged, only
relocated and re-namespaced.

## Motivation

Part of the stratum-lint rule-210 remediation program's `bases/cli`
batch. `components_test.clj` is a test file with zero fan-in repo-wide
(confirmed via a fully-qualified namespace grep), so the split carries
no call-site risk. The four layers were a fixture chain (analysis → PR →
fleet) plus the tests that consumed each fixture at increasing depth;
pulling the chain into its own namespace collapses every consuming test
back to a single layer.

## Changes in Detail

- New `components_test/fixtures.clj` — shared sample data
  (`sample-analysis`, `sample-selected-pr`, `sample-fleet`; 3 layers, the
  fixture-dependency chain the other three files build on).
- New `components_test/page_and_status_test.clj` — the page-chrome and
  workflow-status widget tests, neither of which needs any PR fixture
  (1 layer).
- New `components_test/detail_panel_test.clj` — the two detail-panel
  states, empty and PR-selected (1 layer, uses `fixtures/sample-selected-pr`).
- New `components_test/dashboard_test.clj` — the top-level dashboard
  orchestrator test (1 layer, uses `fixtures/sample-fleet` and
  `fixtures/sample-selected-pr`).
- Deleted `components_test.clj`, superseded by the above.

The source file under test, `components.clj`, is being split
concurrently by a separate change; this split only touches the test
namespace's internal structure and does not depend on that source
split's outcome.

## Testing Plan

- `stratum-lint` clean (exit 0) on all four new files.
- Manual `clojure.test/run-tests` across the three test namespaces: 5
  tests, 17 assertions, 0 failures/errors — identical to the pre-split
  baseline.
- `tasks/commit_budget.clj` and `tasks/pr_budget.clj` both pass on the
  full diff.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 program's `bases/cli` batch (see
  PR #1764 `overlay_test.clj` and the `mdc_to_pack_mapping_test.clj`
  train, PRs #1758-#1778, for the established test-split convention
  this follows).

## Checklist

- [x] stratum-lint clean on all four resulting files
- [x] Manual `clojure.test/run-tests`: 5 tests / 17 assertions, matches baseline
- [x] Adversarial self-review: deftest/assertion set unchanged, only relocated
- [x] Zero fan-in confirmed repo-wide before starting
