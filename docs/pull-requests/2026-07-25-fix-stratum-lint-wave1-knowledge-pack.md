# fix: stratum-lint autofix for components/knowledge-pack (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/knowledge-pack` (`src` + `test`)
to replace decorative `Layer N` headings with real ones + `^{:stratum n}`
metadata derived from each file's actual same-file reference graph.
Mechanically driven; two follow-up docstring corrections were needed where
the fix changed a file's real layer count and left the ns docstring's own
"how many strata" claim stale. One of the smaller per-component Wave 1 PRs
from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`knowledge-pack` carried 2 findings under the baseline's cargo-cult
diagnosis, both `SL003` (over-budget heading count) on the two test files:
`pack_test.clj` (5 distinct layers) and `verify_test.clj` (4 distinct
layers). Zero `SL001` findings, so no upward-reference/cycle risk to
reason about before running the mechanical fixer — matches the baseline's
Wave 1 batch criteria exactly.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/knowledge-pack
```

All 6 files rewritten: `interface.clj`, `pack.clj`, `schema.clj`,
`verify.clj` (`src`), and `pack_test.clj`, `verify_test.clj` (`test`).

- `interface.clj`: every export collapsed to a single real `Layer 0` — all
  ten defs are pure re-exports of vars from `pack`/`schema`/`verify`, none
  referencing each other in this file. The ns docstring previously claimed
  "Two strata: Layer 0 — schema re-exports. Layer 1 — operation
  re-exports." — now false, since the fix found only one real layer.
  Reworded to state the actual (one-layer) structure.
- `pack.clj`: real depth is 6 layers (0–5) — `content-bearing-fields` /
  `revision-id-from-digest` / `zettel->ref` at Layer 0, rising through
  `content-projection` → `compute-digest` → `stamp-revision` to the public
  constructors (`build-pack`, `update-pack`) at Layer 4 and the manifest
  mutators (`add-zettel`, `remove-zettel`) at Layer 5. The ns docstring
  previously claimed "Two strata mirror the zettel module" — also now
  false; reworded to state the real 6-layer chain and flag it as a
  namespace-split candidate.
- `schema.clj`: 2 real layers (`ZettelRef` at Layer 0, `KnowledgePack` at
  Layer 1) — matched its existing docstring claim exactly, no wording
  change needed.
- `verify.clj`: 3 real layers (0–2), within budget — heading text and
  metadata only.
- `pack_test.clj` / `verify_test.clj`: real depth collapsed from 5→2 and
  4→2 respectively (most `deftest` forms don't reference each other, so
  they land on the same real layer as the helpers or the first consumer).

No line of executable code changed; diffs are heading text,
`^{:stratum n}` metadata, def/deftest reordering, and (in `interface.clj`
and `pack.clj` only) the two docstring corrections above.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's exact 2 findings (`SL003` on both test files).
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency. Re-ran a third time after the two manual
   docstring edits — still zero diff.
3. Read the full diff for all 6 changed files. No same-line trailing
   comment was displaced onto the wrong def — all comments in this
   component were already own-line banners, so that known tool limitation
   doesn't apply here. Found the other known pattern instead (stale
   layer-count claim in a docstring, not a section banner) in
   `interface.clj` and `pack.clj`; both corrected by hand per-occurrence,
   as above.
4. `clj-kondo --lint components/knowledge-pack`: 0 errors, 0 warnings,
   before and after.
5. Ran both test namespaces directly via `clojure -M:test`: 22 tests, 53
   assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004` clear
   everywhere. `SL003` newly appears on `pack.clj` — **not** the same
   finding as before the fix (which reported zero findings for this file);
   the old 2-layer heading under-counted the real reference chain, and
   `--fix` surfaced the true 6-layer depth. Deferred to Wave 2 (real
   namespace split), consistent with how prior Wave 1 PRs (`bb-config`,
   `decision`, `compliance-scanner`) handled the same situation. The two
   pre-existing `SL003` findings on the test files are resolved (both
   collapsed to 2 real layers, within budget).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order/docstring-only. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward;
`pack.clj`'s `SL003` stays advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn`
at commit time) until Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/knowledge-pack/src/ai/miniforge/knowledge_pack/pack.clj`
  (6 real layers, over the 3-layer budget)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second (and third, post-manual-edit) `--fix` pass confirms
      idempotency (zero diff)
- [x] Diff read in full for all 6 changed files; mechanical, plus two
      hand-corrected stale docstring claims
- [x] `clj-kondo` clean before/after (0 errors, 0 warnings)
- [x] Component tests pass (22 tests, 53 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except `SL003`
      (`pack.clj`, newly surfaced, documented above, tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
