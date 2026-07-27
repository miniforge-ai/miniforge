# fix: stratum-lint autofix for components/artifact (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/artifact` (`src` + `test`) to
replace decorative/repeated `Layer N` headings with real ones and
`^{:stratum n}` metadata derived from each file's actual same-file
reference graph. One of the Wave 1 per-component PRs from
`work/stratum-lint-baseline-2026-07-24.md`.

**Not purely mechanical**: `--fix` on `interface.cljc` moved a private
`#?(:bb ... :clj ...)`-wrapped helper (`create-datalevin-store`) from
before its only caller (`create-store`) to the very end of the file,
after all headed layers. The tool does not parse `defn-` forms nested
inside a reader-conditional splice (the whole top-level form is `#?()`,
not the `defn-` itself) as recognized defs, so it never saw the
same-file call from `create-store` and placed the untracked form after
everything else instead of leaving it where a human would need it. That
broke compilation outright (`Unable to resolve symbol:
create-datalevin-store in this context`) — confirmed by loading the
namespace before and after.

First attempt was to hand-restore the original position, but re-running
`--fix` moved it right back to the end (confirmed empirically, not a
one-off) — a hand-fix alone doesn't survive pre-commit's autofixer
re-running on any future edit to this file. Resolved instead by
restructuring the code so the tool can parse it: moved the `#?(:bb ...
:default ...)` split from wrapping the whole top-level `defn-` to living
inside the function body (`(defn- create-datalevin-store [opts] #?(:bb
... :default ...))`), matching the convention `components/datalevin`
already uses for the same bb/JVM split
(`components/datalevin/src/ai/miniforge/datalevin/interface.cljc`, which
uses `:default` rather than `:clj` for the same reason — confirmed by
grepping for other `#?(:bb` uses in the tree). `create-datalevin-store`
is now a normal, always-present `defn-` that `--fix` correctly recognizes
and stratifies at Layer 0; `create-store`, which calls it, correctly
moved to Layer 1. Verified: compiles, `--fix` run a second time against
this file makes no further change (stable), and `clj-kondo` is clean
(switching the body's bb/else split to `:default` also required changing
the file's `:require` reader-conditional from `:clj` to `:default` for
the same alias to resolve under clj-kondo's analysis — the require and
the body must agree on which key selects the JVM branch). Behavior is
unchanged: same throw on `:bb`, same delegation to
`datalevin-store/create-datalevin-store` otherwise. Everything else in
this PR is heading/metadata/reorder only, with no other logic change.

## Motivation

Baseline findings for this component: one `SL003` (`transit_store.clj`,
reported as "6 distinct layers", over the 3-layer budget) and three
`SL002` (`core_test.clj`, a repeated "Layer 0" banner reused across four
different function groups instead of one heading per real stratum). Zero
`SL001` findings, confirmed via a plain (non-`--fix`) lint run before
touching anything — no upward-reference/cycle risk to reason about first,
matching this wave's batch criteria.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/artifact
```

10 files rewritten across `src` and `test`:
`core.clj`, `datalevin_store.clj`, `interface.cljc`,
`interface/protocols/artifact_store.clj`, `messages.clj`,
`protocols/impl/transit_store.clj`, `protocols/records/transit_store.clj`,
`test/core_test.clj`, `test/interface_test.clj`,
`test/protocols/impl/transit_store_test.clj`.

Most files only gained `^{:stratum 0}` tags on their (already
single-layer) defs — no reordering, no prior findings. The two files with
actual findings:

- `transit_store.clj` collapsed from 6 nominal layers to 5 real ones
  (`Layer 0` through `Layer 4`), regrouping helpers like
  `criterion-match?`, `load-indexed-artifact`, and `update-cache-links`
  (previously scattered under stale section comments) to their true
  stratum-0 depth, and moving `close-store` up from its old, wrong
  position at the bottom.
- `core_test.clj` collapsed four repeated "Layer 0" banners (one per
  `deftest` group: `build-artifact`, `add-parent`, `add-child`,
  immutability) into a single real Layer 0 — all these tests genuinely
  have no same-file dependency depth.
- `transit_store_test.clj` had **zero** `Layer` headings before this fix
  (silently skipped by the plain linter — a documented tool limitation:
  files with no heading at all aren't flagged, so this file reported no
  findings pre-fix despite having real structure). `--fix` added
  headings and revealed a genuine 4-layer dependency chain
  (`parent-id`/`artifact`/`temp-store` → `parent-artifact`/`child-artifact`
  → `test-find-link-target`/`test-link-artifacts-success` →
  `test-link-artifacts-missing-targets`).

The `interface.cljc` forward-reference break and its fix are described
above under Overview.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's exactly 4 findings (1 `SL003`, 3 `SL002`), zero `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency of the tool's own output.
3. Read the full diff for all 10 changed files. Found the
   `interface.cljc` forward-reference break (see Overview), confirmed by
   loading the namespace (`clojure -M -e "(require
   'ai.miniforge.artifact.interface)"`) before and after each attempted
   fix — failed with `Unable to resolve symbol` on the tool's raw output;
   still failed after a naive hand-restore once `--fix` was re-run
   (confirmed the relocation is deterministic, not a one-off); succeeded
   once the code was restructured so the reader-conditional split lives
   inside the function body instead of around the whole top-level form.
   Re-ran `--fix` over the whole component again after the restructure —
   zero diff, confirms the final state is a stable fixed point the tool
   itself agrees with, not just a hand-edit sitting untouched. No other
   file showed the trailing same-line-comment displacement or stale
   double-semicolon `Layer N` banner patterns from prior batches.
4. `clj-kondo --lint components/artifact`: 0 errors, 0 warnings (the
   restructure briefly introduced a false "unused binding opts" warning
   under a `:clj`-keyed body branch — resolved by switching to `:default`
   for both the body split and the file's `:require` reader-conditional,
   matching the `components/datalevin` convention).
5. Ran `ai.miniforge.artifact.core-test`,
   `ai.miniforge.artifact.interface-test`, and
   `ai.miniforge.artifact.protocols.impl.transit-store-test` directly via
   `clojure -A:test`: 14 tests, 37 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear. Two `SL003` findings remain:
   - `transit_store.clj` (5 real layers) — **same underlying finding as
     baseline**, now correctly counted (was reported as "6" against the
     old decorative headings; the tool's real reference-graph count is
     5). Not new, deferred to Wave 2.
   - `transit_store_test.clj` (4 real layers) — **newly surfaced**. This
     file had zero findings pre-fix only because it had no `Layer`
     heading at all (see above); the fix's added structure revealed the
     real over-budget depth for the first time. Deferred to Wave 2.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change: `create-datalevin-store`'s restructure only moves where the
`:bb`/`:default` split sits syntactically, not what either branch does.
Pre-commit's `lint:stratum` autofixer will re-run `--fix` against this
component on any future commit touching these files — confirmed stable
(zero diff) against the final state, so this is safe going forward.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on (Wave 2): namespace split for
  `components/artifact/src/ai/miniforge/artifact/protocols/impl/transit_store.clj`
  (5 real layers) and
  `components/artifact/test/ai/miniforge/artifact/protocols/impl/transit_store_test.clj`
  (4 real layers), both over the 3-layer budget.
- **New upstream bug to file against `miniforge-ai/stratum-lint`**:
  `--fix` does not parse `defn`/`defn-` forms nested inside a
  `#?(:bb ... :clj ...)` reader-conditional splice as recognized defs. It
  silently relocates the entire unrecognized top-level form to the end
  of the file, past all `Layer` headings, regardless of same-file
  callers referencing it earlier in the file — this can (and here did)
  break compilation. Worked around in this PR by restructuring the one
  file that hit it (moving the split inside the function body instead of
  around the whole form), but the underlying tool defect is unfixed:
  any other file in the tree with a `#?(:bb ...)`/`#?(:clj ...)`-wrapped
  top-level `defn`/`defn-` that has a same-file caller is at risk of the
  same silent breakage the next time it's touched under `--fix`. Worth a
  codebase-wide grep for that shape (`resume.cljc` in `components/workflow`
  has the wrapping pattern too, but its wrapped def has no same-file
  caller, so it wasn't exposed here) before this recurs elsewhere.

## Checklist

- [x] Zero `SL001` findings confirmed before running `--fix`
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency of the tool's raw output
      (zero diff)
- [x] Diff read in full for all 10 changed files
- [x] Found and fixed a real compile-breaking tool defect
      (`interface.cljc` forward reference) by restructuring the affected
      reader conditional rather than hand-ordering around it; verified
      by loading the namespace before/after and confirming a subsequent
      `--fix` pass makes no further change
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component tests pass (14 tests, 37 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except 2 `SL003` (one
      pre-existing/same, one newly surfaced — both documented above,
      tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
