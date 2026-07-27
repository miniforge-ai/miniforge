# fix: stratum-lint autofix for components/config (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/config` (`src` + `test`) to
replace decorative/mislabeled `Layer N` headings with real ones derived
from the file's actual same-file reference graph, and adds `^{:stratum n}`
metadata to every top-level def. Purely mechanical: no logic changes.
One component-scoped PR from `work/stratum-lint-baseline-2026-07-24.md`
(Wave 1, batch 3).

## Motivation

`components/config` carried exactly 2 findings under the baseline's
cargo-cult diagnosis, both in `user.clj`: `SL002` (a `Layer 0` heading
reused after `Layer 0`, i.e. a heading not strictly increasing) and
`SL003` (file reported 4 distinct layers against the 3-layer budget).
Zero `SL001` findings — no upward-reference/cycle risk to reason about
before running the mechanical fixer — matching the baseline's Wave 1
batch criteria exactly.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/config
```

All 11 files in the component were rewritten (6 `src`, 5 `test`) —
`--fix` normalizes every file passed to it, not just the ones with
findings. `resource.clj` had no prior findings at all (its file predates
any `Layer` heading, so rule 210's checker silently skipped it — a
documented tool limitation) but still gained real headings and
`^{:stratum n}` metadata for the first time. Everywhere else, existing
headings/metadata were recomputed against the real reference graph; a
few functions moved between layers or changed position within a layer
where the old heading undercounted or overcounted the file's actual
dependency depth (e.g. `profile.clj`'s `load-profile` moved from a
stale "Layer 1" position to the reference-graph-correct Layer 1 slot
after `gh-cli-token`; `governance.clj`'s config-loading functions moved
down a layer once their real dependency on `deep-merge`/pattern
compilation was accounted for). No line of executable code changed;
diffs are heading text, `^{:stratum n}` metadata, comment repositioning,
and def/deftest reordering only.

**Hand-fix:** `governance_test.clj` had a pre-existing, non-`Layer`
descriptive divider (`;----... Loading`) directly above its first
`deftest`. `--fix` doesn't recognize dividers without the literal
`Layer N` text as headings, so it left this one untouched but inserted
the new real `Layer 0` heading immediately above it — leaving two
stacked banner-style comments in a row that say nothing beyond what the
`Layer 0` heading (this file collapses to a single real layer) and the
test names (`load-governance-config-*-test`) already convey. Deleted
the now-redundant `Loading` line; re-ran `--fix` twice more afterward
and got zero rewrites both times, confirming the deletion didn't
destabilize anything. Three sibling dividers in the same file (`Regex
Compilation`, `Pack Overrides`, `Regression: Values Match Original
Hardcoded Defaults`) were left alone — each still groups several tests
under one label that isn't otherwise obvious at a glance, and none of
them make a `Layer N` claim to be wrong about, so removing them would
lose information for no correctness gain. Grepped the whole component
for the double-semicolon `;;---- Layer N: <description>` stale-banner
shape seen in earlier Wave 1 batches (e.g. `logging`'s `sinks.clj`) —
zero matches; that specific failure mode doesn't occur here. Also
checked every diff for a same-line trailing comment relocated onto the
wrong def (the other known tool limitation) — none found; every
existing comment in this component was already its own line.

`user.clj` now reports `SL003`: 6 real layers (0-5) against the 3-layer
budget — worse than the 4 layers the old (wrong) heading had claimed.
`--fix` inferring the true reference chain surfaced more depth than the
pre-fix heading admitted to, not something this PR introduces. Deferred
to Wave 2 (real namespace split), consistent with how prior Wave 1 PRs
(`bb-config`, `decision`, `compliance-scanner`) handled the same
situation.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced
   the baseline's exact 2 findings (`SL002` + `SL003`, both `user.clj`).
2. Ran `--fix`, then a second `--fix` pass immediately after — zero
   files rewritten, confirming idempotency.
3. Read the full diff for all 11 changed files. Found one hand-fix-worthy
   case (the stacked divider in `governance_test.clj`, above); applied
   it, then ran `--fix` a third time — zero rewrites, confirming the
   hand-edit is stable.
4. `clj-kondo --lint components/config`: 0 errors, 0 warnings, both
   before (verified via `git stash`) and after the fix.
5. Ran all 5 test namespaces directly via `clojure -A:test`: 36 tests,
   168 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear. `SL003` remains on `user.clj`, now reporting 6 real layers
   (up from the pre-fix heading's claimed 4) — expected, tracked as
   Wave 2, not a defect in this PR.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; `user.clj`'s
`SL003` stays advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit
time) until Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/config/src/ai/miniforge/config/user.clj` (6 real layers,
  over the 3-layer budget)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 11 changed files; mechanical-only aside
      from one hand-fixed redundant comment divider
- [x] Hand-fix (`governance_test.clj` stacked divider) re-verified
      stable via a third `--fix` pass (zero rewrites)
- [x] `clj-kondo` clean before/after (0 errors, 0 warnings)
- [x] Component tests pass (36 tests, 168 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except `SL003`
      (`user.clj`, documented above, tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
