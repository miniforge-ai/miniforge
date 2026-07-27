# fix: stratum-lint autofix for components/connector-pipeline-output (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/connector-pipeline-output` (src +
test) and commits the result: regenerated `;---- Layer N` headings and
`^{:stratum n}` metadata on every top-level def, computed from each file's
real same-file reference graph. No logic changed — verified below. One
component-scoped PR in the Wave 1 batch described in
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

The baseline audit found rule 210 (`standards/miniforge/languages/clojure.mdc`)
had been cargo-culted into decorative section banners across most of the
tree. `connector-pipeline-output` carried a single finding — `SL003` on
`schema.clj` (4 distinct layers under its old headings, over the 3-layer
budget) — and zero `SL001` upward-reference findings, matching the Wave 1
criterion for safe-to-autofix-first: no cycle/upward-call risk to reason
about before running the mechanical fixer.

## Changes in Detail

`stratum-lint --fix` (pinned sha `80699e378cb8ebbb6daeb928431aa4a6b373c07e`,
resolved via `tasks/stratum.clj`) rewrote all 8 Clojure files in the
component — every file, not just the one with a prior finding, since `--fix`
always regenerates canonical headings and tags every def with
`^{:stratum n}`, even in an already-heading-compliant file.

- **src (6 files):** `core.clj`, `format.clj`, `impl.clj`, `interface.clj`,
  `messages.clj`, `schema.clj`
- **test (2 files):** `impl_test.clj`,
  `anomaly/pipeline_output_anomaly_test.clj`

`schema.clj`'s original `SL003` resolved cleanly — its real reference-graph
depth is 3 layers, not 4; the old headings had mis-split `validate` and
`validate-config`/`validate-manifest` across layers that didn't reflect an
actual dependency order.

`impl.clj`, however, surfaced a **new** `SL003`: its old headings only
declared 3 layers (`Layer 0`–`Layer 2`), but the real reference graph —
`do-publish` → `publish-per-dataset!`/`publish-combined!` →
`write-and-validate-manifest!` → `build-manifest`/`schema-validation-anomaly`
— is 4 layers deep. The old headings weren't wrong about ordering, just
under-counting: they'd never been split past `Layer 2` even though the file
already had a fourth real stratum. Not a regression from this change; the
mechanical fix makes a pre-existing depth violation visible rather than
introducing one. Genuinely Wave 2 scope (needs an actual namespace split),
not attempted here.

## Testing Plan

- Plain (non-`--fix`) `stratum-lint` before the fix: reproduced the baseline's
  1 finding exactly (`schema.clj` `SL003`, 0 `SL001`).
- `--fix` run twice in a row before committing; zero diff between the two
  passes (idempotency confirmed directly).
- Read the full diff for all 8 changed files: heading text, `^{:stratum n}`
  metadata, and def reordering only. No same-line trailing comments existed
  in any of the 8 original files (checked directly), so there was no
  comment-reattachment risk to hand-verify — none found.
- `clj-kondo --lint components/connector-pipeline-output`: 0 errors, 0
  warnings.
- Plain lint after fixing: 1 `SL003` remains, `impl.clj` (4 real layers,
  described above) — Wave 2 scope.
- Component test suite (`impl-test` + `pipeline-output-anomaly-test`): 16
  tests, 67 assertions, 0 failures, 0 errors.

## Deployment Plan

Merges to `main`. No behavior change to `connector-pipeline-output` itself —
headings, metadata, and def order only.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for `impl.clj` (4 real layers, `SL003`)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Idempotency verified directly (two `--fix` passes, zero diff)
- [x] Diff reviewed file-by-file; mechanical-only (heading + metadata +
      reorder), no comment-reattachment cases found
- [x] `clj-kondo` clean across the whole component
- [x] Plain lint re-run post-fix: `SL003` remains on `impl.clj` only,
      documented above as Wave 2 scope
- [x] Component tests pass (16 tests, 67 assertions, 0 failures/errors)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
