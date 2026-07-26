<!--
  Title: fix: stratum-lint autofix for components/connector-github (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/connector-github (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/connector-github` (`src` +
`test`) to replace decorative `Layer N` headings with real ones derived
from each file's actual same-file reference graph, and tag every
top-level `def`/`defn`/`deftest` with `^{:stratum n}`. One of the Wave 1
batches from `work/stratum-lint-baseline-2026-07-24.md`. Fully mechanical
— no hand edits were needed beyond the autofix output. No executable
logic changed anywhere.

## Motivation

Baseline findings for this component, confirmed via a fresh plain-lint
run before touching anything (zero `SL001` — no upward-reference/cycle
risk, matching the Wave 1 batch criteria):

- `test/ai/miniforge/connector_github/impl_test.clj`: 10 `SL004` findings
  (every `deftest` in the file appeared before the first `Layer` heading
  — the file had no heading at all) plus 2 `SL002` findings (a `Layer 1`
  heading reused three times, and a `Layer 2` heading reused once, as
  decorative per-group section banners rather than one heading per real
  stratum).

12 findings total, matching the baseline doc's per-component table
exactly (`SL001` 0, `SL002` 2, `SL003` 0, `SL004` 10).

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/connector-github
```

All 8 `.clj` files in the component were rewritten (`--fix` normalizes
every file, not just the ones with findings): `core.clj`, `impl.clj`,
`interface.clj`, `messages.clj`, `resources.clj`, `schema.clj`, and both
test files. Diffs are heading text, `^{:stratum n}` metadata, and
def/deftest reordering only.

Two files had their defs reordered onto a real, deeper dependency chain
than their prior (single, flat) heading implied:

- `resources.clj` collapsed from one flat section into 4 real layers:
  `resource-path`/`build-url`/`build-query-params` (Layer 0) →
  `load-resources` (Layer 1, uses `resource-path`) → `github-resources`
  (Layer 2, delays `load-resources`) → `get-resource`/`resource-schemas`
  (Layer 3, dereference `github-resources`).
- `impl.clj` collapsed into 8 real layers, reflecting a genuine deep
  call chain in the pagination/review-extraction path: `do-extract`
  (Layer 7) → `extract-reviews` (Layer 6) → `pull-review-records` (Layer
  5) → `resource-records` (Layer 4) → `fetch-all-pages` (Layer 3) →
  `response-records` (Layer 2) → `filter-issues` (Layer 1) →
  `issue-not-pr?` (Layer 0). `schema.clj` similarly collapsed into 4 real
  layers (enum vectors → enum schemas → composite `ConnectorMetadata` →
  `validate-metadata`).

`impl_test.clj`'s 3 decorative double-semicolon `;;---- Layer N` banners
(with no trailing label after the number — the plain-text label lived on
a separate following comment line, e.g. `;; Issue filtering tests`) were
removed cleanly by the fix itself along with the rest of the old heading
structure; the descriptive comment lines were left in place untouched.
Read the full diff for all 8 files and found no orphaned/contradictory
banner and no same-line trailing-comment displacement.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before any change — reproduced
   the 12 findings above exactly (10 `SL004` + 2 `SL002`), confirmed 0
   `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero
   diff, confirms idempotency.
3. Read the full diff for all 8 changed files. No stale decorative
   banners, no trailing-comment displacement, no forward-reference
   ordering issues found — each def is used only after its own
   definition in the rewritten file.
4. `clj-kondo --lint components/connector-github`: 0 errors, 0 warnings,
   both before and after (confirmed via `git stash`).
5. Ran both test namespaces directly via `clojure -M:dev:test`: 21
   tests, 74 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear across the component. `SL003` newly surfaces (not present in
   the pre-fix baseline, which only tracks decorative headings) on 3
   files, each genuinely over the 3-layer budget once the real
   dependency depth is counted:
   - `impl.clj`: 8 real layers (the pagination/review-extraction chain
     above).
   - `resources.clj`: 4 real layers.
   - `schema.clj`: 4 real layers.

   All three are real Wave 2 (namespace split) candidates per the
   baseline doc's Wave 4 section — not something to fix in this batch.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; `impl.clj`,
`resources.clj`, and `schema.clj`'s `SL003` stay advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2 splits
them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/connector-github/src/ai/miniforge/connector_github/impl.clj`
  (8 real layers), `resources.clj` (4 real layers), and `schema.clj` (4
  real layers) — all over the 3-layer budget.

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 8 changed files
- [x] No stale decorative banners or trailing-comment displacement found
- [x] `clj-kondo` clean (0 errors, 0 warnings before/after)
- [x] Component tests pass (21 tests, 74 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero `SL001`/`SL002`/`SL004`; `SL003`
      newly surfaces on `impl.clj`, `resources.clj`, and `schema.clj` —
      tracked as Wave 2 above
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
