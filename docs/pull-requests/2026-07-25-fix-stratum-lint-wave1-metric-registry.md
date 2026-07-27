# fix: stratum-lint autofix for components/metric-registry (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/metric-registry` (src + test) and
commits the result: regenerated `;---- Layer N` headings and `^{:stratum n}`
metadata on every top-level def, computed from each file's real same-file
reference graph. No logic changed. One PR in the Wave 1 batch described in
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

The baseline audit found rule 210 (`standards/miniforge/languages/clojure.mdc`)
had been cargo-culted into decorative section banners across most of the
tree. `metric-registry` carried exactly one finding — `SL002` in
`schema.clj` (a `Layer 2` heading repeated instead of strictly increasing) —
and zero `SL001` upward-reference findings, meeting the baseline's criterion
for safe mechanical autofix with no cycle/reasoning risk to check first.

## Changes in Detail

`stratum-lint --fix` (pinned sha `80699e378cb8ebbb6daeb928431aa4a6b373c07e`)
rewrote all 5 Clojure files in the component — every file, not just
`schema.clj`, because `--fix` always regenerates canonical headings and
tags every def with `^{:stratum n}`, even in files that had no prior
findings:

- **src (4 files):** `interface.clj`, `lookup.clj`, `messages.clj`,
  `schema.clj`
- **test (1 file):** `interface_test.clj`

`schema.clj`'s real reference graph turned out deeper than its old,
partially-honest 3-heading layout suggested: `--fix` computed 6 distinct
strata (0–5) from actual same-file dependencies, not the 3 the old
headings implied.

## Testing Plan

- `--fix` run twice in a row before committing; second pass produced no
  rewrites and the working tree was byte-identical to the first pass —
  idempotency confirmed directly.
- Read the full diff for every changed file. All changes are heading
  regrouping, `^{:stratum n}` metadata, and def reordering. No comments in
  this component's files were same-line trailing comments to begin with
  (checked pre-fix content directly), so none were at risk of
  misattachment; none moved incorrectly.
- `clj-kondo --lint components/metric-registry`: 0 errors, 0 warnings.
- Plain (non-fix) lint after fixing: one `SL003` remains —
  `schema.clj` genuinely uses 6 distinct layers against the budget of 3.
  Pre-existing real depth, not decorative; needs an actual namespace split
  (Wave 2 scope), not attempted here.
- Ran the component's tests directly (`clojure -M:dev:test`, requiring
  `ai.miniforge.metric-registry.interface-test`): 11 tests, 30 assertions,
  0 failures, 0 errors.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
changes — headings, metadata, and def order only. The pre-commit hook's
`lint:stratum` autofixer keeps this component clean going forward; the
remaining `SL003` finding on `schema.clj` stays advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn`) until Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for `schema.clj` (`SL003`, 6 real
  layers)

## Checklist

- [x] `--fix` run over the whole component (src + test)
- [x] Idempotency verified directly (two `--fix` passes, zero diff)
- [x] Diff reviewed file-by-file; mechanical-only (headings + metadata +
      reorder)
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Plain lint re-run post-fix: only `SL003` remains, documented as
      Wave 2 scope
- [x] Tests pass: 11 tests, 30 assertions, 0 failures/errors
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
