# fix: remove unused column binding in connector-linter etl

## Overview

`record->violation` (`components/connector-linter/src/ai/miniforge/connector_linter/etl.clj`)
extracted a `column` local via `(extract-field record (get fields :column))`
but never referenced it in the body — clj-kondo flagged it as an unused
binding. Removes the dead binding. The pre-commit stratum-lint autofix
also rewrote this file's `;-- Layer N` headings and added `^{:stratum n}`
metadata to match its real reference-graph layering (mechanical, no logic
change), riding along in the same commit since it always runs on any
commit touching the file.

## Motivation

Confirmed via `git stash`/`git show` against `origin/main` that this
unused binding predates the recent stratum-lint Wave 1 mechanical passes
over other connector components — it isn't something those PRs
introduced, and it wasn't fixed there to keep those diffs mechanical-only
(`--fix`, headings + metadata, no hand edits).

Before deleting it, checked whether `column` should instead be threaded
into the violation map — every one of the six linter mapping specs
(`clj-kondo`, `clippy`, `eslint`, `golangci-lint`, `ruff`, `swiftlint` in
`components/connector-linter/resources/connector_linter/linter-mappings.edn`)
declares a `:column` field, so the extraction looked deliberate. Traced
every downstream consumer of `connector-linter`'s produced violations:

- `bases/cli/src/ai/miniforge/cli/main/commands/scan.clj` (`run-linters`)
  — reads `:tech`, `:available?`, `:violations`, `:duration-ms` only.
- `components/gate/src/ai/miniforge/gate/pre_verify_lint.clj`
  (`violation->lint-error`) — destructures `:file`, `:line`, `:current`,
  `:rule/id`, `:rule/severity` only.
- `components/compliance-scanner/src/ai/miniforge/compliance_scanner/comments.clj`
  (`violation->comment`, PR review comments) — projects `:line` only, no
  `:comment/column` field exists.
- `connector-github` — no reference to column/`start_column`/Checks-API
  annotation payloads anywhere.
- The canonical `Violation` shape
  (`ai.miniforge.compliance-scanner.factory/->violation`) has no
  column/position slot at all — `:column` only shows up as an ad hoc key
  on violations built *outside* the factory (e.g.
  `compliance-scanner/named_constants.clj`), an unrelated scan path.

Nothing in the codebase reads column/position data from a
connector-linter-produced violation. Threading it into the violation map
here would add a key nobody consumes — the same kind of speculative,
unused-for-now field this fix is removing, just moved one hop over.
Removing the binding is the correct fix, not a stopgap.

## Changes in Detail

- `components/connector-linter/src/ai/miniforge/connector_linter/etl.clj`
  — deleted the unused `column` let-binding in `record->violation`. No
  other logic change.
- Same file: pre-commit stratum-lint autofix reorganized headings into
  the real 4-layer structure (`extract-field`/`map-severity`/
  `parse-json-safe`/`mappings-resource` at Layer 0; `record->violation`/
  `matches-filter?`/the `extract-records-*` variants/`mappings` at Layer
  1; `extract-records`/`get-mapping` at Layer 2; `apply-mapping` at Layer
  3) and added `^{:stratum n}` metadata to every def. This 4-layer shape
  is pre-existing (unrelated to the binding removal — the call graph
  depth doesn't change), and is a genuine **SL003: 4 distinct layers (max
  3)** finding needing an actual namespace split — that's Wave 2 work per
  `work/stratum-lint-baseline-2026-07-24.md`, out of scope for this fix.
  Committed with `MINIFORGE_STRATUM_BUDGET_MODE=warn`, the documented
  opt-out for exactly this pre-existing-backlog situation (see
  `docs/pull-requests/2026-07-24-fix-sl003-blocking-by-default.md`).

## Testing Plan

1. `clj-kondo --lint components/connector-linter` — 0 errors, 0 warnings
   (previously 1 unused-binding warning).
2. `clojure -M:poly test brick:connector-linter` — 33 assertions across
   3 projects (`miniforge`, `miniforge-core`, `miniforge-tui`), 0
   failures, 0 errors, both before and after the stratum-lint
   reorganization.
3. Read the full diff — confirmed the only behavioral change is the
   deleted binding; the rest is heading/metadata/reordering.

## Deployment Plan

Merges to `main`. No callers outside `record->violation` itself; no
behavior change (the binding was never read). Nothing to roll out or
monitor.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 2 will need
  to split `etl.clj`'s 4 real layers)
- Precedent for the `warn`-mode commit: `docs/pull-requests/2026-07-24-fix-sl003-blocking-by-default.md`

## Checklist

- [x] clj-kondo clean on `components/connector-linter`
- [x] Component test suite green: 33/33 assertions, 0 failures, 0 errors
- [x] Downstream consumers of connector-linter violations audited;
      confirmed no data-loss regression from removing `column`
- [x] Pre-existing SL003 (4-layer) finding documented as Wave 2 work, not
      a defect in this fix
