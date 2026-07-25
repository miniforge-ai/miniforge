# fix: stratum-lint autofix for components/tool (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/tool` (src + test) and commits
the result: regenerated `;---- Layer N` headings and `^{:stratum n}`
metadata on every top-level def, computed from each file's real same-file
reference graph. No logic changed. One PR in the Wave 1 batch described in
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

The baseline audit found rule 210 (`standards/miniforge/languages/clojure.mdc`)
had been cargo-culted into decorative section banners across most of the
tree. `components/tool` carried exactly one finding — `SL003` in
`interface.clj` (4 distinct layers against the budget of 3, from headings
that didn't track real dependency depth) — and zero `SL001`
upward-reference findings, meeting the baseline's criterion for safe
mechanical autofix with no cycle/reasoning risk to check first.

## Changes in Detail

`stratum-lint --fix` (pinned sha `80699e378cb8ebbb6daeb928431aa4a6b373c07e`,
read from `tasks/stratum.clj`) rewrote all 6 Clojure files in the
component:

- **src (4 files):** `core.clj`, `interface.clj`, `messages.clj`,
  `tracking.clj`
- **test (2 files):** `anomaly_shape_test.clj`, `interface_test.clj`

`interface.clj`'s functions are thin wrappers delegating straight to
`core`/`tracking` with no same-file references between them, so their real
stratum collapsed to a single Layer 0 — the old 4-layer split was
decorative. `core.clj` and `tracking.clj` picked up a genuine second
(and, for `core.clj`, third) layer from real same-file calls (e.g.
`tracking/build-invocation` depending on `instant-from-ms`), and both test
files' `deftest` forms were regrouped by which helpers they actually call.

## Testing Plan

- `--fix` run twice in a row; second pass produced no rewrites (no
  "rewrote" output) and a direct diff of all 6 files against the
  first pass's output was empty — idempotency confirmed directly.
- Read the full diff for every changed file. All changes are heading
  regrouping, `^{:stratum n}` metadata, and def/deftest reordering. One
  same-line trailing comment existed pre-fix (`core.clj`'s `tool-schema`);
  checked it explicitly — it stayed attached to the same def, only
  whitespace before it changed. No comment moved to a different def.
- `clj-kondo --lint components/tool`: 0 errors, 0 warnings.
- Plain (non-fix) lint after fixing: exit 0, no findings remain — the
  original `SL003` resolved because the real dependency graph fits within
  the 3-layer budget once headings track it honestly. No Wave 2 follow-up
  needed for this component.
- Ran the component's tests directly from the repo root classpath
  (`clojure -M:test`, requiring `ai.miniforge.tool.interface-test` and
  `ai.miniforge.tool.anomaly-shape-test`): 18 tests, 85 assertions, 0
  failures, 0 errors.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
changes — headings, metadata, and def order only. The pre-commit hook's
`lint:stratum` autofixer keeps this component clean going forward.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- No follow-on: post-fix lint reports zero remaining findings for this
  component (no Wave 2 namespace split needed)

## Checklist

- [x] `--fix` run over the whole component (src + test)
- [x] Idempotency verified directly (two `--fix` passes, zero diff)
- [x] Diff reviewed file-by-file; mechanical-only (headings + metadata +
      reorder); one pre-existing same-line comment checked, confirmed
      correctly attached
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Plain lint re-run post-fix: zero findings remain
- [x] Tests pass: 18 tests, 85 assertions, 0 failures/errors
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
