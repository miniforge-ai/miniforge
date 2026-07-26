# fix: stratum-lint autofix for components/reporting (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/reporting` (`src` + `test`) to
replace decorative `Layer N` headings with ones derived from the file's
actual same-file reference graph, and tags every top-level def/deftest with
`^{:stratum n}` metadata. Mechanical: no logic changes. One of the
per-component Wave 1 PRs from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`components/reporting` carried 4 findings under the baseline's cargo-cult
diagnosis, all `SL003` (heading claims more distinct layers than the
3-layer budget): `core.clj` (claimed 6), `interface.clj` (claimed 5),
`interface_test.clj` (claimed 6), `views_test.clj` (claimed 9). Zero
`SL001` findings, so no upward-reference/cycle risk to reason about before
running the mechanical fixer.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/reporting
```

All 11 files rewritten (`--fix` normalizes every file in the component,
not just the ones with findings): `core.clj`, `interface.clj`,
`protocol.clj`, `views/edn.clj`, `views/formatting.clj`, `views/meta.clj`,
`views/system.clj`, `views/workflow.clj`, `core_test.clj`,
`interface_test.clj`, `views_test.clj`.

Notable collapses, all matching the real reference graph:

- `interface.clj`'s defs each delegate to another namespace
  (`proto/`, `core/`, `view-*`) and never call each other in-file, so all
  6 defs (previously spread across 5 decorative layers) land at real
  Layer 0.
- `views/formatting.clj`'s `format-table` doesn't call any of `ansi`,
  `draw-box`, `draw-separator`, or the color/box-char tables, so it moves
  from the old Layer 2 down to real Layer 0 alongside the other
  no-same-file-dependency defs; `ansi`/`draw-box`/`draw-separator` (which
  do reference the data tables) land at Layer 1.
- `core.clj` collapses from 6 decorative layers to 4 real ones
  (`safe-get`/`count-by-status`/`create-subscription`/`build-workflow-timeline`/`get-workflow-logs`
  at 0; the `aggregate-*`/`collect-alerts`/`get-workflow-artifacts` helpers
  that call them at 1; `ReportingServiceImpl` at 2; the
  `create-reporting-service` constructor at 3).
- `interface_test.clj` and `views_test.clj` reorder `deftest` forms by
  whether they call same-file fixtures/sample data (Layer 1) or not
  (Layer 0), collapsing 6 and 9 decorative layers to 2 real ones each.

No line of executable code changed; diffs are heading text, `^{:stratum
n}` metadata, and def/deftest reordering only. The one same-line trailing
comment in the component (`(atom {})  ; subscriptions` in
`create-reporting-service`) stayed attached to its own def; no stale
double-semicolon banner text survived; the component has no
`defmethod`/`defmulti`, so the multimethod-stratum bug this pin already
fixes doesn't apply here.

`core.clj` now reports `SL003`: 4 real layers (0–3) against the 3-layer
budget. This is the same finding as before the fix (it already reported
`SL003` pre-fix, at a decorative count of 6) — `--fix` corrected the count
to the real depth, it didn't introduce a new violation. Deferred to Wave 2
(real namespace split), consistent with how prior Wave 1 PRs (`bb-config`,
`decision`, `compliance-scanner`) handled the same situation.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's 4 `SL003` findings exactly; confirmed zero `SL001` findings
   before proceeding.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 11 changed files. Confirmed only heading
   text, `^{:stratum n}` metadata, and def/deftest reordering changed; no
   same-line trailing comment was displaced onto the wrong def; no stale
   `;;----`-style banner survived.
4. `clj-kondo --lint components/reporting`: 0 errors, 0 warnings.
5. Ran `ai.miniforge.reporting.core-test`, `ai.miniforge.reporting.interface-test`,
   and `ai.miniforge.reporting.views-test` directly via `clojure -A:test`:
   31 tests, 115 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear across the component. `SL003` remains on `core.clj` (4 real
   layers) — expected, tracked as Wave 2, not a defect in this PR.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; `core.clj`'s `SL003`
stays advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until
Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/reporting/src/ai/miniforge/reporting/core.clj` (4 real
  layers, over the 3-layer budget)

## Checklist

- [x] Confirmed zero `SL001` findings before running `--fix`
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 11 changed files; mechanical-only
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component tests pass (31 tests, 115 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except `SL003` (`core.clj`,
      documented above, tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
