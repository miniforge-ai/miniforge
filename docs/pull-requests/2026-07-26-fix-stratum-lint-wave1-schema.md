# fix: stratum-lint autofix for components/schema (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/schema` (`src` + `test`) to
replace decorative `Layer N` headings with real ones derived from each
file's actual same-file reference graph, and tag every top-level `def`/
`defn`/`deftest` with `^{:stratum n}`. One of the Wave 1 batches from
`work/stratum-lint-baseline-2026-07-24.md`. Mostly mechanical, but one
manual fix was needed beyond the autofix output: a stale decorative
`;---- Layer 0a` banner in `supervisory.clj` survived the tool untouched
and was deleted (see Changes in Detail), and three namespace docstrings
that hardcoded a now-wrong layer count were rewritten to match the real
structure the fix produced. No executable logic changed anywhere.

## Motivation

Baseline findings for this component, confirmed via a fresh plain-lint
run before touching anything (zero `SL001` — no upward-reference/cycle
risk, matching the Wave 1 batch criteria):

- `interface.clj`: `SL003`, 4 distinct (decorative) layers against the
  3-layer budget.
- `interface_test.clj`: `SL002`, a `Layer 1` heading reappearing after
  `Layer 1` — a duplicate section banner sitting after the file's
  trailing `(comment ...)` block, not a real second stratum.
- `supervisory_test.clj`: `SL003`, 9 distinct layers — one decorative
  `Layer N` banner per `deftest` group.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/schema
```

All 10 `.clj` files in the component were rewritten (`--fix` normalizes
every file, not just the ones with findings): `core.clj`, `interface.clj`,
`logging.clj`, `supervisory.clj`, and all 6 test files. Diffs are heading
text, `^{:stratum n}` metadata, and def/deftest reordering only.

Two things the autofix itself resolved correctly and are worth calling
out because they look alarming in a raw diff:

- `interface_test.clj`'s duplicate `Layer 1` heading (4 severity-related
  `deftest`s stranded after the file's `(comment ...)` block) was
  collapsed: those tests, plus two more that were already correctly at
  the real Layer 0, all moved up next to the fixtures. No test was
  dropped — `36 tests / 139 assertions` before and after.
- `supervisory_test.clj`'s 9 decorative per-`deftest`-group banners
  collapsed to 2 real layers (fixtures, then everything that uses them —
  none of the 8 `deftest` groups actually depend on each other).

One thing the autofix did **not** resolve, found during the mandated
full-diff read (`work/stratum-lint-baseline-2026-07-24.md`'s known
tool-limitation class (b) — a stale decorative banner surviving next to
a new real heading): `supervisory.clj` had an old `;---- Layer 0a`
sub-banner (with its own "Registry extensions for supervisory types"
comment) sitting directly below the fix's new, correct `;---- Layer 1`
heading — a leftover from the pre-fix file's `Layer 0 / Layer 0a / Layer
1` decorative scheme. Deleted both lines by hand: the `supervisory-registry`
def already carries an equivalent docstring, so the banner added nothing
and directly contradicted the heading above it. Re-ran `--fix` a third
time afterward — zero diff, confirms the hand edit is stable.

Also updated by hand: `core.clj`, `logging.clj`, and `supervisory.clj`
each carry a namespace-docstring summary of their own layer structure
(e.g. `"Layer 0: Base types and registries \n Layer 1: Composite
schemas"`). The fix changed the real layer count in all three (2→4, 2→4,
2→3 respectively) without touching the docstrings, leaving them
describing a structure the file no longer has. Rewrote each to list its
actual layers/contents.

No `defmethod` in this component, so the upstream `defmethod`-refs-union
bug class (already fixed at this pin) doesn't apply here.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before any change — reproduced
   the 3 findings above exactly, confirmed 0 `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 10 changed files. Found and hand-fixed the
   stale `Layer 0a` banner in `supervisory.clj` (above); no same-line
   trailing-comment displacement found in any file.
4. Ran `--fix` a third time after the hand edits — zero diff, confirms
   the manual fix is stable under the tool.
5. `clj-kondo --lint components/schema`: 0 errors both before and after.
   8 pre-existing warnings (deprecated `schema/validate` used by its own
   backward-compat test coverage) are unchanged in content and count
   before and after — only line numbers shifted from reordering. Not
   introduced by this change.
6. Ran all 6 test namespaces directly via `clojure -M:test`: 36 tests,
   139 assertions, 0 failures, 0 errors.
7. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear across the component. `SL003` remains, newly surfaced (not
   present in the pre-fix baseline run) on two files:
   - `core.clj`: 4 real layers (was 2 decorative, under budget) —
     `--fix` inferred the true chain `severities`/`normalize-severity` →
     `severity-order`/`registry` → `compare-severity`/`Severity`/most
     composite schemas → `more-severe`/`Task`/`Artifact`/`Workflow`.
   - `logging.clj`: 4 real layers (was 2 decorative, under budget) —
     true chain is the base vocabularies → `all-events` → `logging-registry`
     → the composite schemas.

   Both are genuinely over the 3-layer budget the old decorative headings
   hid by undercounting, not a regression this PR introduces — deferred
   to Wave 2 (real namespace split), consistent with how prior Wave 1
   PRs (e.g. `bb-config`) handled the same situation. `interface.clj`'s
   original `SL003` and `supervisory_test.clj`'s original `SL003` are
   both fully resolved (collapsed to 2 real layers each, under budget).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order/docstring-only. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward;
`core.clj` and `logging.clj`'s `SL003` stay advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2 splits
them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/schema/src/ai/miniforge/schema/core.clj` and
  `components/schema/src/ai/miniforge/schema/logging.clj` (4 real layers
  each, over the 3-layer budget)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 10 changed files
- [x] Stale decorative `Layer 0a` banner (`supervisory.clj`) found and
      removed by hand; third `--fix` pass confirms it's stable
- [x] Three namespace docstrings (`core.clj`, `logging.clj`,
      `supervisory.clj`) updated to match the real post-fix layer
      structure
- [x] `clj-kondo` clean of new issues (0 errors before/after; 8
      pre-existing warnings unchanged in content/count)
- [x] Component tests pass (36 tests, 139 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero `SL001`/`SL002`/`SL004`; `SL003`
      remains on `core.clj` and `logging.clj` — newly surfaced by the
      fix (not pre-existing), tracked as Wave 2 above
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
