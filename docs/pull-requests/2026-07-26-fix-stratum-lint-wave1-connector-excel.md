<!--
  Title: fix: stratum-lint autofix for components/connector-excel (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/connector-excel (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/connector-excel` (`src` + `test`)
to replace missing `Layer N` headings with real headings and
`^{:stratum n}` metadata derived from each file's actual same-file
reference graph. Mechanical: no logic changes. One of the Wave 1 batch 5
per-component PRs from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

Plain (non-`--fix`) `stratum-lint` on `components/connector-excel` reported
only `SL004` (defs before the first `Layer` heading), all seven in the same
test file, zero `SL001`/`SL002`/`SL003`:

```text
components/connector-excel/test/ai/miniforge/connector_excel/interface_test.clj:24:1: SL004 'cell-value-nil-test' appears before the first Layer heading
components/connector-excel/test/ai/miniforge/connector_excel/interface_test.clj:28:1: SL004 'parse-sheet-column-mapping-test' appears before the first Layer heading
components/connector-excel/test/ai/miniforge/connector_excel/interface_test.clj:52:1: SL004 'parse-sheet-with-filter-test' appears before the first Layer heading
components/connector-excel/test/ai/miniforge/connector_excel/interface_test.clj:72:1: SL004 'parse-sheet-missing-sheet-test' appears before the first Layer heading
components/connector-excel/test/ai/miniforge/connector_excel/interface_test.clj:79:1: SL004 'extract-enriches-series-id-test' appears before the first Layer heading
components/connector-excel/test/ai/miniforge/connector_excel/interface_test.clj:100:1: SL004 'extract-decimal-year-date-format-test' appears before the first Layer heading
components/connector-excel/test/ai/miniforge/connector_excel/interface_test.clj:123:1: SL004 'connect-validates-config-test' appears before the first Layer heading
```

Zero `SL001`, confirming this component carries no upward-reference/cycle
risk requiring human triage before running the mechanical fixer. The other
five files (`core.clj`, `impl.clj`, `interface.clj`, `messages.clj`,
`anomaly/excel_anomaly_test.clj`) reported no findings at all pre-fix, but
had no `Layer N` heading anywhere either — invisible to the checker under
the documented "no heading = silently skipped" limitation, not a clean bill
of health.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/connector-excel
```

All 6 `.clj` files in the component were rewritten (4 `src`, 2 `test`).
Diffs are heading insertion, `^{:stratum n}` metadata, and def/deftest
reordering only — no executable line changed.

- `impl.clj` had the most reordering: `--fix` regrouped its defs into 4
  real layers by actual same-file reference depth (handle-registry
  accessors and pure helpers at Layer 0/1, `parse-sheet`/`do-connect`/
  `do-close`/`do-discover` at Layer 2, `do-extract` at Layer 3, since it
  calls into `parse-sheet` and `normalize-record`). This exceeds the
  3-layer budget — see Testing Plan.
- `interface_test.clj` carried one stale double-semicolon `Layer 2` banner
  (`;;---- Layer 2` above `discover-returns-anomaly-on-unknown-handle-test`)
  that predated any real heading in the file and was never recognized by
  the tool's heading regex. `--fix` dropped it entirely as part of
  regrouping all seven tests to real Layer 0 (none of the tests reference
  each other or any same-file helper beyond `impl` functions), leaving the
  still-accurate "Migrated handle-lookup helper" comment in place with no
  contradiction. No hand-fix needed — verified by reading the resulting
  file in full.
- `core.clj`, `messages.clj`, `interface.clj`, and
  `anomaly/excel_anomaly_test.clj` each got a single Layer 0 heading
  inserted plus `^{:stratum 0}` metadata on their (in each case, one-layer)
  defs — no reordering, since each file's defs are already independent of
  each other.

No same-line trailing comment was displaced onto the wrong def — grepped
the diff for the known `foo])  ; comment` migration pattern; no matches.

## Testing Plan

1. Ran plain `stratum-lint` before the fix — reproduced the seven `SL004`
   findings above exactly, zero `SL001`/`SL002`/`SL003`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 6 changed files. Confirmed only heading
   insertion, `^{:stratum n}` metadata, and def/deftest reordering
   changed. Confirmed no orphaned/contradictory decorative headings
   remained (grepped for `;;----`/`;----` across the component; only the
   regenerated single-semicolon real headings are present).
4. `clj-kondo --lint components/connector-excel`: 0 errors, 0 warnings.
5. Ran the component's test namespaces directly (`clojure -M:dev:test -e
   "..."` requiring and running `ai.miniforge.connector-excel.interface-test`
   and `ai.miniforge.connector-excel.anomaly.excel-anomaly-test`): 15
   tests, 39 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix. `SL001`/`SL002`/`SL004`
   clear. One new `SL003` finding:

   ```text
   components/connector-excel/src/ai/miniforge/connector_excel/impl.clj:221:1: SL003 file uses 4 distinct layers (max 3); split the namespace or extract a component
   ```

   `impl.clj`'s true reference-graph depth is 4 real layers (over the
   3-layer budget) — this file had no headings at all pre-fix, so the
   over-budget condition was invisible to the checker before this PR, not
   newly introduced by it. Real namespace-split scope for Wave 2, not
   addressed here. Committed with `MINIFORGE_STRATUM_BUDGET_MODE=warn`.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; `impl.clj` stays
advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until
Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for `impl.clj` (4 real layers, over
  the 3-layer budget).

## Checklist

- [x] Zero `SL001` confirmed before running `--fix` (no upward-reference/
      cycle risk requiring human triage)
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 6 changed files; mechanical-only
- [x] No orphaned/contradictory decorative headings found
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component tests pass (15 tests, 39 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: `SL003` newly surfaced on `impl.clj`
      (4 real layers), documented above, tracked as Wave 2
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
