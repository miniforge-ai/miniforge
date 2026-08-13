<!--
  Title: Split cli/main/commands/evidence.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split evidence.clj (rule 210)

## Overview

Splits bundle discovery/loading and field-derivation helpers out of
`ai.miniforge.cli.main.commands.evidence` into a new sibling
namespace, `ai.miniforge.cli.main.commands.evidence.bundles`,
resolving a stratum-lint SL003 finding (the combined namespace
measured 5 real layers, over the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program's `bases/cli`
batch — 13 `main/commands/*.clj` files are being split concurrently.
`evidence.clj` (238 lines) is one of them.

## Changes in Detail

- New file `commands/evidence/bundles.clj`: `evidence-dir`,
  `load-bundle-from-file`, `bundle-detail-spec`,
  `phase-evidence-keys`, `active-dependency?`, `label`,
  `display-component-bundles` (layer 0), and `scan-evidence-dir`,
  `dependency-issue-count`, `failure-attribution-summary`,
  `canonical-phase-names`, `load-bundle-for-show`,
  `export-bundle-fallback` (layer 1) — 2 layers. Everything about
  locating/loading a bundle (filesystem scan or the optional
  `ai.miniforge.evidence-bundle.interface` component) and deriving
  its normalized/summary fields.
- `evidence.clj`: keeps the three command entry points
  (`evidence-list-cmd`, `evidence-show-cmd`, `evidence-export-cmd`)
  and the detail-view rendering they share
  (`normalize-bundle-detail`, `display-filesystem-bundles`,
  `display-bundle-detail`) — now 3 layers (down from 5), calling
  `bundles/*` for everything that moved.
- Visibility flips (`defn-` -> `defn`), the only change beyond code
  motion and call-site qualification — each of these is now called
  cross-namespace from `evidence.clj`: `evidence-dir`,
  `bundle-detail-spec` (dropped `^:private`), `scan-evidence-dir`,
  `dependency-issue-count`, `failure-attribution-summary`,
  `canonical-phase-names`, `load-bundle-for-show`,
  `export-bundle-fallback`, `display-component-bundles`.
  `phase-evidence-keys`, `active-dependency?`, and `label` stay
  private — used only internally within `bundles.clj`.
  `load-bundle-from-file` was already public and is unchanged.
- `evidence_test.clj`: the two `load-bundle-from-file-test`
  assertions called the raw function directly (white-box) — updated
  to `bundles/load-bundle-from-file` since that fn moved. Every other
  `sut/*` call (the three command entry points) is unchanged.
- `bases/cli/src/ai/miniforge/cli/main.clj`'s `cmd-evidence` alias is
  unaffected — it only ever calls the three command entry points,
  none of which moved, so no update needed there.

This is pure code motion aside from the required visibility changes
and the one test call-site update above — no behavior changed.

## Testing Plan

- `stratum-lint` clean on both resulting files (exit 0, was SL003
  exit 1 on the original 5-layer file — confirmed against a control
  copy of `main` before the split).
- `clj-kondo` clean on all three touched files (0 errors, 0
  warnings).
- `ai.miniforge.cli.main.commands.evidence-test` run directly (not
  relying on `bb test` alone): `clojure -M:dev:test -e "(require
  'ai.miniforge.cli.main.commands.evidence-test)
  (clojure.test/run-tests
  'ai.miniforge.cli.main.commands.evidence-test)"` — 8 tests, 12
  assertions, 0 failures, 0 errors. Run once before committing and
  again after both commits landed.
- `bb pre-commit` (commit-budget, `poly check`, clj-kondo,
  stratum-lint, `fmt:md`, the 18-namespace pre-commit smoke suite —
  345 tests / 1301 assertions, 0 failures — and the GraalVM
  compatibility suite — 8 tests / 624 assertions, 0 failures) passed
  on both commits in this PR.
- Repo-wide grep for the fully-qualified namespace
  (`ai\.miniforge\.cli\.main\.commands\.evidence\b`) across
  `components/`, `bases/`, and `projects/` found exactly two callers:
  `bases/cli/src/ai/miniforge/cli/main.clj` (unaffected, see above)
  and `bases/cli/test/.../evidence_test.clj` (updated). No
  project-level test callers.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.
Sibling `main/commands/*.clj` splits in this batch continue
independently.

## Related Issues/PRs

- Part of the stratum-lint rule-210 program's `bases/cli` batch (13
  concurrent `main/commands/*.clj` splits). Follows the convention
  established by `gh pr diff 1772` (loader.clj) and `gh pr diff 1731`
  (knowledge_safety.clj).

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] `bb pre-commit` green on both commits (full smoke + GraalVM
      suites included)
- [x] Adversarial self-review: def set unchanged except the nine
      defn-/def -> public visibility flips, documented above
- [x] Test call sites updated for the one white-box
      `load-bundle-from-file` test
- [x] Zero fan-in surprises confirmed repo-wide before and after the
      split
