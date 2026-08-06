<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: stratum-lint autofix for components/web-dashboard (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/web-dashboard` (`src` + `test`)
to replace decorative `Layer N` banners and missing headings with real
`Layer N` headings and `^{:stratum n}` metadata derived from each file's
actual same-file reference graph. Mechanical: no logic changes. Also
hand-fixes 20 stale decorative `;; Layer N: <label>` comments across 4
files that now contradict the recomputed real headings, and one genuine
execution-order bug in a test file where `--fix`'s appendix-sweep behavior
moved a same-file-order-sensitive `require` call to the wrong place (see
Changes in Detail). One of the Wave 1 batch 5 per-component PRs from
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

Plain (non-`--fix`) `stratum-lint` on `components/web-dashboard` reported
11 findings, all `SL002`/`SL003`/`SL004`, **zero `SL001`**:

```text
filter_eval.clj:91:1: SL003 file uses 4 distinct layers (max 3)
filters.clj:291:1: SL003 file uses 7 distinct layers (max 3)
server/control_plane.clj:227:1: SL003 file uses 4 distinct layers (max 3)
server/handlers.clj:781:1: SL003 file uses 8 distinct layers (max 3)
state/trains.clj:1078:1: SL003 file uses 5 distinct layers (max 3)
state/workflows.clj:505:1: SL003 file uses 8 distinct layers (max 3)
views/control_plane.clj:70:1: SL002 Layer 0 heading appears after Layer 0
views/control_plane.clj:94:1: SL002 Layer 0 heading appears after Layer 0
views/control_plane.clj:227:1: SL002 Layer 2 heading appears after Layer 2
views/workflows.clj:24:1: SL004 'ms-per-second' before first Layer heading
views/workflows.clj:25:1: SL004 'ms-per-minute' before first Layer heading
```

Zero `SL001`, confirming this component carries no upward-reference/cycle
risk requiring human triage before running the mechanical fixer.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/web-dashboard
```

46 of the component's `.clj` files were rewritten (33 `src`, 13 `test`).
Two files that reported `SL003` pre-fix — `server/handlers.clj` (8 layers)
and `state/workflows.clj` (8 layers) — were **not** rewritten: both already
carried exactly correct, strictly-increasing real `Layer 0`–`Layer 7`
headings and `^{:stratum n}` metadata; `--fix` had nothing to change,
confirming these are genuinely over-budget files, not mislabeled ones.

Verified no functional content moved or changed beyond reorder + metadata:
for every rewritten file, stripped comments/blank lines/`^{:stratum n}`
annotations from both the pre-fix and post-fix text and diffed the sorted
result — empty for all 46 files (only cosmetic double-space artifacts from
the stripping itself). Also grepped the diff for the known same-line
trailing-comment displacement pattern (`foo])  ; comment`) — no matches.

**Decorative banner cleanup (20 instances, 4 files).** Double-semicolon
`;; Layer N: <label>` / `<label> (Layer N)` comments left behind by earlier
manual edits, invisible to `--fix`'s heading regex, now contradicted the
regenerated real headings:

- `components.clj`: a pure re-export namespace — every def is real
  Layer 0 (aliases, no same-file references), but `;; Layer 1: Layouts`
  and `;; Layer 2: Data Display` implied a depth this file doesn't have.
  Dropped the wrong numbers, kept the labels.
- `state/trains_test.clj`: 11 decorative `Layer 1`–`Layer 4` labels sat
  inside what is now (correctly) a single real `Layer 0` span, plus one
  decorative `Layer 0` sitting inside the real `Layer 1` span. All were
  artifacts of one banner per `deftest` group, not real depth. Dropped the
  wrong numbers, kept each descriptive label.
- `state/trains_classify_test.clj`: `classify-error-category (Layer 3)`
  and `pr-ready? (Layer 1)` — both groups are real Layer 0. Dropped the
  parenthetical.
- `views/control_plane_test.clj`: 5 of 6 `Layer N —` labels contradicted
  the real Layer 0/Layer 1 split (3 claimed `Layer 0` inside the real
  `Layer 1` span; 2 claimed `Layer 2` also inside `Layer 1`). One
  (`Layer 1 — decision-item / decision-queue-fragment`) already matched
  its real stratum and was left alone.

Re-ran `--fix` after these edits — zero diff, confirming the manual edits
didn't interact with stratum computation (comments aren't part of the
reference graph).

**Genuine bug found and fixed: `state/fleet_test.clj`.** This file
originally had a bare top-level `(require '[...fleet :as sut])` placed
after its fixtures and before the first `deftest` referencing `sut/...` —
deliberately, per its own comment ("require the SUT after defining
fixtures"). `--fix` swept this unrecognized non-`def` top-level form into
the file's appendix (after every real layer, i.e. after every `deftest`
that needs `sut` aliased already) — the exact appendix-sweep failure mode
flagged in the Wave 1 runbook. Confirmed via `clj-kondo`: post-fix, it
newly reported `Unresolved namespace sut` at the first `sut/...` use,
absent pre-fix. First tried the runbook's prescribed workaround (wrap the
call in a `def` so `--fix` places it at its real computed stratum instead
of the appendix) — this does fix Clojure's real compile-order requirement,
but introduces a *new* clj-kondo blind spot: kondo doesn't track a
`require` nested inside a `def`/`defonce` body the way it tracks a bare
top-level one, so it still reported `sut` unresolved even after the
require executed at the correct time. Investigated further and found no
real justification for deferring the require at all — `fleet_test.clj` is
a leaf test namespace, `fleet.clj` doesn't depend on it, so there's no
circularity the deferral could have been working around, and every other
test file in this component just aliases its SUT in the `ns` form's
`:require` clause. Fixed it that way instead (removed the runtime
`require` and the wrapper entirely, added
`[ai.miniforge.web-dashboard.state.fleet :as sut]` to `:require`) — this
is simpler than the workaround, sidesteps the whole appendix-sweep
question, and is a real (tiny) behavior-preserving cleanup, not just a
lint fix. Re-ran `--fix` twice after — zero diff both times.

## Testing Plan

1. Ran plain `stratum-lint` before the fix — reproduced the 11 findings
   above exactly (6 `SL003`, 3 `SL002`, 2 `SL004`), zero `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 46 changed files; confirmed via the
   normalized-content multiset check (above) that nothing beyond
   heading/metadata/order changed. Found and hand-fixed the 20 stale
   decorative `Layer N` comments and the `fleet_test.clj` require-order
   bug described above; re-ran `--fix` after each round of manual edits —
   zero diff both times, confirming stability.
4. `clj-kondo --lint components/web-dashboard`: 0 errors, 2 warnings
   (`unused binding state` at `server/handlers.clj:717` and `:742`).
   Confirmed pre-existing via `git stash` + re-lint on the unmodified
   tree — same 2 warnings, nothing else. (An earlier intermediate state,
   before the `fleet_test.clj` fix above was finalized, briefly
   introduced 2 new warnings; both are gone in the final diff.)
5. Ran the component's test namespaces directly via `clojure -M:dev:test
   -e` (15 namespaces, `src` + `test`, from repo root — the component's
   own `deps.edn` is missing a transitive local dep
   (`ai.miniforge/control-plane`) that `server/control_plane.clj` actually
   needs, pre-existing and masked by the root `:dev`/`:test` classpath
   flattening, unrelated to this diff): **287 tests, 874 assertions, 0
   failures, 0 errors**.
6. Re-ran plain `stratum-lint` after the fix. `SL001`/`SL002`/`SL004`
   clear. `SL003` remains on 17 files (up from 6 pre-fix — the old
   headings under-counted real depth on most of them once measured from
   the true reference graph):
   `archive.clj` (4), `filter_eval.clj` (4), `filter_schema.clj` (4),
   `filter_specs.clj` (4), `filters.clj` (5), `server/auth.clj` (4),
   `server/filters.clj` (5), `server/handlers.clj` (8),
   `server/websocket.clj` (4), `state/archive.clj` (4),
   `state/trains.clj` (10), `state/workflows.clj` (8), `views.clj` (7),
   `views/control_plane.clj` (5), `views/dag.clj` (7),
   `views/evidence.clj` (4), `views/workflows.clj` (6). All real
   over-budget files (Wave 2 scope: namespace split), not addressed here.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change beyond the `fleet_test.clj` require-clause cleanup (behavior-
preserving — same alias, same target namespace, established before the
same set of call sites that needed it before). Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward; the 17
`SL003` files stay advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2 splits
them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for the 17 `SL003` files listed
  above, `state/trains.clj` (10 real layers) and `server/handlers.clj` /
  `state/workflows.clj` (8 each) being the most over budget.

## Checklist

- [x] Confirmed zero `SL001` before running `--fix`
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 46 changed files; verified mechanical-only
      via normalized-content comparison
- [x] 20 stale decorative `Layer N` comments across 4 files hand-fixed
      (comment-only); `--fix` re-run confirms stability
- [x] Genuine `fleet_test.clj` require-order bug found (introduced by
      `--fix`'s appendix sweep) and fixed at the root cause, not papered
      over
- [x] `clj-kondo` clean (0 errors, 2 pre-existing warnings, confirmed via
      `git stash`)
- [x] Component tests pass (287 tests, 874 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: `SL003` remains on 17 files, documented
      above with precise counts, tracked as Wave 2
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
