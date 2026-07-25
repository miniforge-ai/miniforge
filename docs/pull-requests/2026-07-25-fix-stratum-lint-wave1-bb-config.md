# fix: stratum-lint autofix for components/bb-config (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/bb-config` (`src` + `test`) to
replace the file's pre-heading `def` with a real `Layer N` heading and
`^{:stratum n}` metadata derived from the file's actual same-file
reference graph. Purely mechanical: no logic changes. One of the smaller
per-component Wave 1 PRs from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`bb-config` carried exactly one finding under the baseline's cargo-cult
diagnosis: `SL004` — `core.clj`'s `default-filename` constant sat above
the file's first `Layer` heading. Zero `SL001` findings, so no
upward-reference/cycle risk to reason about before running the mechanical
fixer — matches the baseline's Wave 1 batch criteria exactly.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/bb-config
```

3 files rewritten: `core.clj`, `interface.clj` (both `src`), and
`core_test.clj` (`test`) — `--fix` normalizes every file in the component,
not just the one with the finding. `default-filename` now carries
`^{:stratum 0}`; `default-path`, `load`, and `get` each moved up one real
layer from where their old (honest-looking but wrong) headings placed
them, since each composes the one below it. `interface.clj` and the test
file only gained `^{:stratum n}` tags and reordering — no prior findings
of their own. No line of executable code changed; diffs are heading text,
metadata, and def/deftest reordering only.

`core.clj` now reports `SL003`: 4 real layers (0–3) against the 3-layer
budget, surfaced by `--fix` inferring the true reference chain
(`default-filename` → `default-path` → `load` → `get`). Pre-existing
depth the old decorative headings under-reported, not something this fix
introduces — deferred to Wave 2 (real namespace split), consistent with
how prior Wave 1 PRs (`decision`, `compliance-scanner`) handled the same
situation.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's single `SL004` finding exactly.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 3 changed files. Confirmed only heading
   text, `^{:stratum n}` metadata, and def reordering changed; no
   same-line trailing comment was displaced onto the wrong def (this file
   has none — all comments were already own-line banners, so the known
   tool limitation doesn't apply here).
4. `clj-kondo --lint components/bb-config`: 0 errors, 0 warnings before
   and after (same two pre-existing `info`-level notices about unused
   `:refer-clojure :exclude` vars, one line later post-reorder).
5. Ran `ai.miniforge.bb-config.core-test` directly via `clojure -M:test`:
   4 tests, 7 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear. `SL003` remains on `core.clj` (4 real layers) — expected,
   tracked as Wave 2, not a defect in this PR.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; `core.clj`'s `SL003`
stays advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until
Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for `components/bb-config/src/ai/miniforge/bb_config/core.clj`
  (4 real layers, over the 3-layer budget)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 3 changed files; mechanical-only
- [x] `clj-kondo` clean before/after (0 errors, 0 warnings)
- [x] Component tests pass (4 tests, 7 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except `SL003` (`core.clj`,
      documented above, tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
