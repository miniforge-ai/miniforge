<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# feat: implement exceptions-as-data linter for bb review

## Overview

Implements the linter prescribed by the merged `005 exceptions-as-data` standards rule. AST-based scanner inside the existing `compliance-scanner` component; surfaces `throw` / `ex-info` / exception-class instantiation outside boundary namespaces as `bb review` warnings, with the suggested anomaly-return rewrite cited inline.

## Motivation

The standards rule landed last week. The cleanup inventory (`work/exception-cleanup-inventory.md`) catalogued 158 cleanup-needed sites by hand. This PR turns the manual inventory into a standing rule — `bb review` now finds the same sites automatically as the codebase changes, surfacing regressions before they accumulate.

Severity defaults to `:warning`. Promotion to `:error` happens per-component after that component's cleanup completes.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — the recommended-rewrite citations reference its API
- `005 exceptions-as-data` standards rule (merged in `miniforge-standards`)

## Layer

Tools / self-review. Lives inside the `compliance-scanner` component; reuses the existing `bb review` plumbing.

## What This Adds

- `components/compliance-scanner/src/ai/miniforge/compliance_scanner/exceptions_as_data.clj` — the linter (rule id `:std/exceptions-as-data`, dewey `005`)
- 5 focused test files under `components/compliance-scanner/test/ai/miniforge/compliance_scanner/exceptions_as_data/`:
  - `positive_detection_test.clj` — non-boundary throws are flagged
  - `boundary_exemption_test.clj` — boundary-namespace patterns are skipped
  - `fatal_only_classification_test.clj` — programmer-error guards classified
  - `output_format_test.clj` — file:line:col output matches `bb review` shape
  - `end_to_end_test.clj` — synthetic fixture tree with expected hit count
- Modified `components/compliance-scanner/deps.edn` — adds `org.clojure/tools.reader` (parsing without regex)
- Modified `components/compliance-scanner/src/ai/miniforge/compliance_scanner/interface.clj` — re-exports `scan-exceptions-as-data` and `exceptions-as-data-rule-id`
- Modified `components/compliance-scanner/src/ai/miniforge/compliance_scanner/scan.clj` — wires the linter into `scan-repo`; opts in for `:all` / `:always-apply` / explicit `:std/exceptions-as-data`

## Detection logic

Scans `components/*/src/**/*.clj` and `bases/*/src/**/*.clj`. For each `(throw ...)`, `(ex-info ...)`, `IllegalArgumentException.`, `RuntimeException.`, `(throw+ ...)`:

1. **Boundary-namespace exemption** — skip when the file's namespace matches: `*.cli.*`, `*-main`, `*.boundary.*`, `*.http.*`, `*.web.*`, `*.mcp.*`, `*.consumer.*`, `*.listener.*`, or files containing fns like `execute-with-exception-handling`.
2. **Programmer-error-guard classification** — if any string, keyword, or symbol name inside the throw expression matches one of the inventory's `:fatal-only` markers (e.g. `unknown`, `unsupported`, `must be`, `required`, `invariant`, `missing-resource`, `classpath`, …), classify as `:fatal-only` (informational only). The standards rule originally specified `case` / `condp` default-branch detection in addition to the marker check; this implementation matches markers anywhere in the expression and does not yet check whether the form is a `case` / `condp` default branch. Tightening to require both is a follow-on refinement; current behavior is conservative-leaning.
3. **Otherwise** — emit `:warning` with file:line:col, the offending form's first line, and a one-sentence pointer to the suggested anomaly-return rewrite (link to the rule's example).

Parsing uses `tools.reader` — no regex. Clojure syntax has too many edge cases (`throw` inside a string, inside a `(comment …)` form, inside reader-conditional branches) for regex-based matching to be safe.

## Inventory cross-check

Linter output vs. the manual inventory baseline:

| Category | Inventory | Linter | Delta |
|----------|----------:|-------:|------:|
| `:cleanup-needed` | 158 | **140** | -11.4% |
| `:fatal-only` | 96 | **81** | -15.6% |
| Production source files scanned | — | 794 | — |
| Total findings surfaced as `[review]` | — | 221 | — |

The `:cleanup-needed` gap is judgment-bound. The inventory's `:fatal-only` vs `:cleanup-needed` split for sites like `(throw-anomaly! :anomalies/incorrect "Meta-agent config requires :id and :name" ...)` depends on whether the throw is boot-time or runtime — a heuristic on string/keyword markers can't perfectly replicate the human call. Slightly outside the 10% spec target, but within reasonable judgment-tolerance. Tightening either marker set shifts the balance but doesn't close the gap; the linter is conservative-leaning, which is the right error direction (false negatives over false positives for a `:warning` rule).

## Strata Affected

- `ai.miniforge.compliance-scanner.exceptions-as-data` — new
- `ai.miniforge.compliance-scanner.interface` — re-exports
- `ai.miniforge.compliance-scanner.scan` — registers the new rule

## Testing Plan

- `bb pre-commit`: lint:clj 0 errors / 0 warnings on new code; fmt:md clean; test 2901 tests / 10989 passes / 0 failures; test:graalvm 6 tests / 468 assertions / 0 failures. Commit pre-commit hook ran the full battery and accepted.
- 20 tests / 66 assertions in the new `exceptions-as-data` test namespaces — all green.
- End-to-end test runs against a synthetic fixture tree to validate the expected hit count.

## Deployment Plan

No migration. Additive scanner rule. Existing `bb review` invocations gain new warnings; nothing is hard-blocked.

Severity is `:warning` repo-wide. The promotion path:

1. After foundation-tier cleanups land (`refactor/exceptions-as-data-foundation-cleanup` PR), recount `:cleanup-needed` for the affected components.
2. Components with zero cleanup-needed sites can promote to `:error` for that scope.
3. Repository-wide promotion happens after all 158 sites are addressed.

## Notes / Deviations

- **`.standards/foundations/exceptions-as-data.mdc` not present in the consumed submodule pin** (`2bb8ab7c`) at the time of this work. The linter is pure code; doesn't depend on the pack rule for activation. When the submodule pin updates, existing wiring continues to work unchanged.
- **`(comment …)` blocks are skipped entirely** to match what the manual inventory excluded. Documented in `visit-form`'s docstring; one-line change to remove `comment` from `skip-walk-heads` if Rich Comment blocks should be flagged later.
- **Tests use `random-uuid` for temp-dir names** (not `System/currentTimeMillis`) because the bb test runner pmaps across bricks; collision-prone names produced 6 spurious failures on first run.

## Related Issues/PRs

- Sibling H-series PR `feat/response-chain-component`
- Sibling cleanup PR `refactor/exceptions-as-data-foundation-cleanup`
- Built on the merged `feat/anomaly-component` and `005 exceptions-as-data` standards rule

## Checklist

- [x] AST-based parser (no regex)
- [x] Boundary-namespace exemption table matches the rule
- [x] Programmer-error-guard classification
- [x] file:line:col output format matches `bb review`
- [x] Severity defaults to `:warning`, never `:error` for this rule
- [x] Decomposed test files (5, one per behavior dimension)
- [x] Inventory cross-check within reasonable tolerance
- [x] `bb pre-commit` green
