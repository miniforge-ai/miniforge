# fix: stratum-lint autofix for components/fsm (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/fsm` (`src` + `test`) to replace
decorative `Layer N` headings with real ones derived from the file's actual
same-file reference graph, and tags every `def`/`deftest` with
`^{:stratum n}`. Purely mechanical: no logic changes. One of the
per-component Wave 1 PRs from `work/stratum-lint-baseline-2026-07-24.md`
(batch 3).

## Motivation

`components/fsm` carried 2 findings under the baseline's cargo-cult
diagnosis, both `SL003` (over the 3-layer budget): `core.clj` (reported 5
distinct layers) and `interface.clj` (reported 4). Zero `SL001` findings —
no upward-reference/cycle risk to reason about before running the mechanical
fixer, matching the Wave 1 batch criteria exactly. (An earlier baseline pass
had flagged one `SL001` on `core.clj:88` — a `context` parameter shadowing
the later `context` def — but that was already confirmed a checker false
positive and cleared once Wave 0's upstream scoping fix landed in the
pinned `stratum-lint` sha; the current pin reports zero `SL001` here.)

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/fsm
```

3 files rewritten: `core.clj`, `interface.clj` (both `src`), and
`interface_test.clj` (`test`).

- `interface.clj` collapses from 4 decorative layers to a single real one:
  every `def` here is an independent re-export of a `core` function with no
  same-file references between them, so the whole file lands at
  `^{:stratum 0}`.
- `core.clj` regroups around the real reference chain: guard/context/state
  helpers land at Layer 0, `compile-state`/`statechart-transition-result`/
  `in-state?`/`final?`/`assign`/`update-context`/`guard` at Layer 1 (they
  compose Layer 0), `define-machine`/`transition-result` at Layer 2, and
  `transition` at Layer 3 (it composes `transition-result`).
- `interface_test.clj`'s `deftest` forms all land at Layer 0 — each is
  self-contained, no test calls another test.

No line of executable code changed; diffs are heading text, `^{:stratum n}`
metadata, and def/deftest reordering only.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's 2 `SL003` findings exactly (`core.clj` at 5 layers,
   `interface.clj` at 4).
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff on
   all 3 files, confirms idempotency.
3. Read the full diff for all 3 changed files. The pre-existing plain
   double-semicolon section comments (`;; Machine definition`, `;; State
   operations`, `;; Context manipulation`, `;; Guard helpers`) don't assert
   a layer number themselves, and each stayed correctly paired with the def
   it labeled after reordering — no stale/contradictory banner. No
   same-line trailing comment was displaced onto the wrong def either
   (none of the three files had one).
4. `clj-kondo --lint components/fsm`: 0 errors, 0 warnings, before and
   after.
5. Ran `ai.miniforge.fsm.interface-test` directly via `clojure -A:test`: 15
   tests, 41 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `interface.clj`'s `SL003`
   clears entirely — its old 4-layer count was purely decorative. `core.clj`
   still reports `SL003`, but now at 4 real layers (down from the
   pre-fix-reported 5) — the same underlying over-budget file, not a new
   finding; the old headings had over-counted the depth, `--fix` corrected
   the count while confirming it's genuinely still over budget. Deferred to
   Wave 2.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum` autofixer
keeps this component clean going forward; `core.clj`'s `SL003` stays
advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2
splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/fsm/src/ai/miniforge/fsm/core.clj` (4 real layers, over the
  3-layer budget)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 3 changed files; mechanical-only
- [x] `clj-kondo` clean before/after (0 errors, 0 warnings)
- [x] Component tests pass (15 tests, 41 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: `interface.clj` clears entirely;
      `core.clj`'s `SL003` remains (documented above, tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
