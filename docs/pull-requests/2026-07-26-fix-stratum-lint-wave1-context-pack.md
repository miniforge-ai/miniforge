# fix: stratum-lint autofix for components/context-pack (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/context-pack` (`src` + `test`)
to replace decorative `Layer N` headings with ones derived from each
file's actual same-file reference graph, and to tag every `def`/`defn`/
`deftest` with real `^{:stratum n}` metadata. One of the smaller
per-component Wave 1 PRs from `work/stratum-lint-baseline-2026-07-24.md`.

No logic changes. Four manual documentation corrections beyond the raw
`--fix` output, all the same shape: a namespace docstring asserting a
single `Layer N` (or an explicit `Layer 0/1/2` breakdown) that the fix
falsified once the real same-file reference graph was computed.

- `interface.clj` claimed an internal `Layer 0 / Layer 1 / Layer 2`
  breakdown left over from the old decorative headings; every def in
  that file collapsed to Layer 0 (each just delegates to another
  namespace, so there's no same-file reference depth between them).
  Reworded to drop the numbered-layer claim while keeping the section
  list.
- `dedup.clj`, `schema.clj`, and `config.clj` each claimed "Layer 0" (or
  "no dependencies" / "no domain logic") for the whole file, which is
  true of their *inter-file* dependencies (none of these three require
  another context-pack namespace) but no longer true of their *intra-file*
  structure once `--fix` exposed real same-file chains: `dedup.clj` and
  `schema.clj` each have 2 real layers, `config.clj` has 4 (see below).
  Caught during automated PR review (Copilot flagged `dedup.clj`
  specifically); checking the other files with the same "Layer 0 — pure
  X" phrasing turned up the same problem in `schema.clj` and
  `config.clj`, so all three got the same treatment: drop the blanket
  single-layer claim, state the real intra-file dependency instead,
  point at the per-def `:stratum` metadata for the full breakdown.

## Motivation

Baseline findings for this component: 4 `SL002` (`factory.clj` x3,
`interface.clj` x1) — a `Layer 0` heading repeated as a section banner
instead of appearing once. Zero `SL001` findings, so no upward-reference
or cycle risk to reason about before running the mechanical fixer —
matches the Wave 1 batch criteria.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/context-pack
```

All 8 files rewrote (`--fix` normalizes every file in the component, not
just the ones with findings): `budget.clj`, `builder.clj`, `config.clj`,
`dedup.clj`, `factory.clj`, `interface.clj`, `schema.clj`, and
`interface_test.clj`.

- `factory.clj` — 4 defs, all pure data constructors with no same-file
  calls between them. The 4 repeated `Layer 0` banners collapsed to one;
  each `defn` now carries `^{:stratum 0}`.
- `interface.clj` — every def re-exports another namespace's schema or
  delegates to another namespace's function; none reference each other
  in-file, so every def landed at `^{:stratum 0}`, replacing the old
  `Layer 0/1/2` grouping.
- `schema.clj` — `Source` and `BudgetAudit` are independent
  (`^{:stratum 0}`); `ContextPack` references `Source` in its `:sources`
  key, so it moved to `^{:stratum 1}`.
- `dedup.clj` — `dedup-files` calls `path-not-seen?`, so it moved from
  its old position to `^{:stratum 1}`; the other three functions are leaf
  and stayed at `^{:stratum 0}`.
- `budget.clj` — real 3-layer chain confirmed correct:
  `estimate-tokens`/`would-exceed?`/`tokens-remaining`/`exhausted?`
  (`^{:stratum 0}`) → `add-source` (`^{:stratum 1}`) → `try-add-item`
  (`^{:stratum 2}`).
- `builder.clj` and `config.clj` — see below; both now report `SL003`.
- `interface_test.clj` — all 11 `deftest` forms are independent (no
  same-file calls between tests), so all collapsed to `^{:stratum 0}`;
  reordering here has no behavioral effect.

`builder.clj` and `config.clj` each surfaced a **new** `SL003` (4 real
layers, over the 3-layer budget) that the baseline scan did not report:

- `config.clj` had no `Layer` heading at all before the fix — the tool
  silently skips headingless files (a documented limitation), so its true
  4-layer chain (`config-path` → `load-budget-config` → `budget-config` →
  the five public getters) was invisible to the baseline count.
- `builder.clj` already had 3 headings (`Layer 0/1/2`) in strictly
  increasing order, so it never tripped `SL002`/`SL003` — but the
  grouping under those headings was wrong: `try-add-search-hit` was
  filed under the same heading as `add-repo-map`/`add-files`/etc. even
  though it calls `snippet-text`, and `add-search-results` was filed one
  heading below `build-pack`/`extend-pack` even though it calls
  `try-add-search-hit`. The corrected reference graph needs a real 4th
  layer (`build-pack`/`extend-pack` → `add-search-results` →
  `try-add-search-hit` → `snippet-text`).

Both are Wave 2 scope (real namespace split), not fixed here.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's 4 `SL002` findings exactly, zero `SL001`.
2. Ran `--fix`, then a second and third `--fix` pass immediately after —
   zero diff on both repeats, confirms idempotency.
3. Read the full diff for all 8 changed files. No same-line trailing
   comment was displaced onto the wrong def, and no `defmethod` exists in
   this component (the two known tool-limitation patterns from the Wave 1
   playbook don't apply here). Found and hand-fixed one stale docstring
   (`interface.clj`, see Overview); re-ran `--fix` afterward to confirm
   the manual edit is stable (no further rewrite).
4. `clj-kondo --lint components/context-pack`: 0 errors, 0 warnings.
5. Ran `ai.miniforge.context-pack.interface-test` directly via
   `clojure -A:dev:test`: 11 tests, 29 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear. `SL003` now reports on `builder.clj` and `config.clj` (4 real
   layers each) — both newly surfaced by the corrected grouping, not
   pre-existing findings; documented above and deferred to Wave 2.
7. Automated PR review flagged the same stale-docstring shape in
   `dedup.clj`; checking the other files carrying the same "Layer 0 —
   pure X" phrasing (`schema.clj`, `config.clj`) turned up the identical
   problem in both. Hand-corrected all three (see Overview), then re-ran
   `--fix` (no further rewrite — stable), `clj-kondo` (still 0/0), the
   plain lint (same two `SL003` findings, nothing new), and the test
   namespace (still 11 tests, 29 assertions, 0 failures/errors).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order/docstring-only. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward;
`builder.clj` and `config.clj`'s `SL003` stay advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2 splits
them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/context-pack/src/ai/miniforge/context_pack/builder.clj` and
  `components/context-pack/src/ai/miniforge/context_pack/config.clj`
  (4 real layers each, over the 3-layer budget)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second and third `--fix` passes confirm idempotency (zero diff)
- [x] Diff read in full for all 8 changed files; four stale docstrings
      hand-corrected across two review passes (documented above), no
      logic changes
- [x] `clj-kondo` clean before/after (0 errors, 0 warnings)
- [x] Component tests pass (11 tests, 29 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except `SL003`
      (`builder.clj`, `config.clj` — newly surfaced, documented above,
      tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
