<!--
  Title: stratum-lint autofix for components/connector-edgar (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/connector-edgar (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/connector-edgar` (`src` +
`test`) to replace decorative `Layer N` headings with real ones derived
from each file's actual same-file reference graph, and tag every
top-level `def`/`defn`/`deftest` with `^{:stratum n}`. Batch 5 of Wave 1
from `work/stratum-lint-baseline-2026-07-24.md`. Purely mechanical — no
executable logic changed anywhere.

## Motivation

A fresh plain-lint run before touching anything confirmed zero `SL001`
(no upward-reference/cycle risk), matching the Wave 1 batch criteria.
All 6 pre-existing findings were `SL004` (a `deftest` appearing before
any `Layer` heading), concentrated entirely in
`interface_test.clj`, which had no headings at all.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/connector-edgar
```

All 6 `.clj` files in the component were rewritten (`--fix` normalizes
every file, not just the ones with findings): `core.clj`, `impl.clj`,
`interface.clj`, `messages.clj`, and both test files. Diffs are heading
text, `^{:stratum n}` metadata, and def/deftest reordering only.

`core.clj`, `interface.clj`, and `messages.clj` each collapsed to a
single real `Layer 0` — every def in those files was already at the
base stratum, just missing the heading and metadata.

`impl.clj` is the substantial diff: `--fix` recomputed **7 real layers**
(0 through 6) from the file's actual call graph — handle-registry
plumbing and pure helpers (`parse-xml`, `monthly-windows`,
`count-by-code`, etc.) at Layer 0, up through `search-filings` →
`fetch-filing-transactions` → `aggregate-buy-sell-ratio` → `do-extract`
at Layer 6, the deepest chain being the EFTS-search → fetch-transactions
→ aggregate → extract pipeline. This produces a new `SL003` (over the
3-layer budget) that did not show up in the pre-fix baseline, because
the file previously had no headings at all to measure against — see
Testing Plan and Deployment Plan.

`interface_test.clj` had one old decorative double-semicolon banner
(`;;------------------------------------------------------------------------------ Layer 2`)
sitting above a `;; Migrated handle-lookup helper...` comment. Unlike
the contradictory-banner class seen in earlier batches, `--fix` removed
this one cleanly on its own — every `deftest` in the file resolved to
the same real Layer 0, so there was nothing left for the old banner to
contradict. Verified by reading the full post-fix file: the descriptive
comment survives intact above its `deftest`, no orphaned heading
remains. No hand edit was needed.

No `defmethod` in this component, so the upstream `defmethod`-refs-union
bug class doesn't apply here. No reader-conditional-wrapped defs, so the
SL008 restructuring pattern (`components/artifact`) wasn't needed either.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before any change — reproduced
   the 6 `SL004` findings exactly, confirmed 0 `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero
   diff, confirms idempotency.
3. Read the full diff for all 6 changed files. No same-line
   trailing-comment displacement found in any file; the one decorative
   banner found (`interface_test.clj`) was resolved cleanly by the tool
   itself, not left contradictory — confirmed by inspection, no hand
   edit required.
4. `clj-kondo --lint components/connector-edgar`: 0 errors, 0 warnings,
   both before and after.
5. Ran both test namespaces directly via `clojure -M:dev:test`:
   `ai.miniforge.connector-edgar.interface-test` and
   `ai.miniforge.connector-edgar.anomaly.edgar-anomaly-test` — 12 tests,
   42 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear across the component. One `SL003` remains, newly surfaced (not
   present in the pre-fix baseline, since the file had no headings to
   measure against before): `impl.clj` at 7 real layers against the
   3-layer budget — a genuine Wave 2 (namespace split) candidate, not a
   defect in this fix.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Committed with
`MINIFORGE_STRATUM_BUDGET_MODE=warn` alongside
`MINIFORGE_COMMIT_BUDGET_OVERRIDE=1` so the pre-commit gate's post-fix
lint pass doesn't hard-block on the newly surfaced `impl.clj` `SL003`.
Pre-commit's `lint:stratum` autofixer keeps this component clean going
forward for everything except that one over-budget file, which stays
advisory until Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/connector-edgar/src/ai/miniforge/connector_edgar/impl.clj`
  (7 real layers, over the 3-layer budget)

## Checklist

- [x] Confirmed zero `SL001` findings before making any change
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 6 changed files
- [x] One decorative banner found in `interface_test.clj`; verified the
      tool resolved it cleanly with no orphaned contradiction — no hand
      edit needed
- [x] `clj-kondo` clean (0 errors, 0 warnings, before and after)
- [x] Component tests pass (12 tests, 42 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero `SL001`/`SL002`/`SL004`; `SL003`
      remains on `impl.clj` — newly surfaced by the fix, tracked as
      Wave 2 above
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
