<!--
  Title: Stratum-lint autofix for components/connector-sarif (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/connector-sarif (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/connector-sarif` (`src` + `test`)
to replace decorative `Layer N` headings with real ones derived from each
file's actual same-file reference graph, and tag every top-level `def`/
`defn`/`defrecord`/`deftest` with `^{:stratum n}`. One of the Wave 1 batches
from `work/stratum-lint-baseline-2026-07-24.md` (batch 5). Purely mechanical
— comment/metadata/def-order only, no executable logic changed.

## Motivation

Baseline plain (non-`--fix`) lint run for this component, before touching
anything, confirmed zero `SL001` (no upward-reference/cycle risk — this
component was not on the pre-vetted SL001-free list, so this was checked
directly rather than assumed):

- `src/.../schema.clj`: `SL003` — 4 distinct (decorative) layers against
  the 3-layer budget.
- `test/.../format_test.clj`: `SL002` ×4 — a `Layer 1` heading repeated
  three more times as a per-test-group section banner.
- `test/.../interface_test.clj`: `SL002` ×2 — same pattern.
- `test/.../schema_test.clj`: `SL002` ×3 — same pattern.

10 findings total, 0 `SL001`.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/connector-sarif
```

All 10 `.clj` files in the component were rewritten (`--fix` normalizes
every file, not just the ones with findings): `core.clj`, `format.clj`,
`impl.clj`, `interface.clj`, `schema.clj`, and all 5 test files. No `SL008`
refusal — no reader-conditional-wrapped def in this component. Diffs are
heading text, `^{:stratum n}` metadata, and def/deftest reordering only.

One thing worth calling out because it looks alarming in a raw diff:
`schema.clj`'s original `SL003` (4 *decorative* layers: `Layer 0` "Enums",
`Layer 1` "Core schemas", `Layer 2` "Validation", `Layer 3` "JSON Schema
export") collapsed to 3 *real* layers once `--fix` recomputed the actual
reference graph — `validate` (the shared helper) turned out to belong at
Layer 0 alongside the enums and `Location`, not at the old Layer 2, since
nothing in the file calls it except `validate-config`/`validate-violation`
which now sit at Layer 1 uses. This resolves the original finding rather
than deferring it.

## Testing Plan

1. Ran plain `stratum-lint` before any change — reproduced the 10 findings
   above exactly, confirmed 0 `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — no files
   listed as rewritten on the second pass, `git status` unchanged: zero
   diff, confirms idempotency.
3. Read the full diff for all 10 changed files. No decorative
   double-semicolon `;;---- Layer N` banners left behind, and no
   same-line trailing comment displaced onto the wrong def — checked
   visually file by file.
4. `clj-kondo --lint components/connector-sarif`: 0 errors, 0 warnings.
   `format_test.clj` previously used fully-qualified `clojure.string/join`
   without a `:require` (a pre-existing warning, confirmed via `git
   stash` + re-lint); added `[clojure.string :as str]` and switched
   `temp-csv!` to the aliased `str/join` while touching the file for
   the heading fix, clearing it.
5. Ran all 5 test namespaces directly (`clojure -M:dev:test`, since
   `:poly test` can sweep in an unrelated pre-existing environment flake
   on this machine): `format-test`, `impl-test`, `interface-test`,
   `schema-test`, `anomaly.sarif-anomaly-test` — 50 tests, 141 assertions,
   0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear across the component. One new `SL003` surfaced — not present in
   the pre-fix baseline — on `format.clj`: 4 real layers (`normalize-severity`
   /`extract-location`/`default-csv-columns`/`find-column-index`/
   `detect-format` → `parse-sarif-result`/`csv-row->violation`/
   `list-scan-files` → `parse-sarif`/`parse-csv` → `parse-file`). This
   file previously carried only 2 decorative headings, undercounting its
   true depth; genuinely over the 3-layer budget, not a regression this
   PR introduces. Deferred to Wave 2 (real namespace split), consistent
   with how prior Wave 1 PRs (e.g. `schema`, `self-healing`) handled the
   same situation. `schema.clj`'s original `SL003` is fully resolved
   (collapsed to 3 real layers, under budget).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; `format.clj`'s
`SL003` stays advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit
time) until Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/connector-sarif/src/ai/miniforge/connector_sarif/format.clj`
  (4 real layers, over the 3-layer budget)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 10 changed files — no orphaned decorative
      banners, no displaced trailing comments
- [x] `clj-kondo` clean of new issues (0 errors before/after; 1
      pre-existing warning unchanged in content, only its line number
      shifted from reordering)
- [x] Component tests pass (50 tests, 141 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero `SL001`/`SL002`/`SL004`; `SL003`
      remains on `format.clj` only — newly surfaced by the fix (not
      pre-existing), tracked as Wave 2 above
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
