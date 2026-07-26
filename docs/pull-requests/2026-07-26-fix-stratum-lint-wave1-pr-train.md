# fix: stratum-lint autofix for components/pr-train (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/pr-train` (src + test) and commits
the result: regenerated `;---- Layer N` headings and `^{:stratum n}` metadata
on every top-level def, computed from each file's real same-file reference
graph. One component-scoped PR in the Wave 1 batch described in
`work/stratum-lint-baseline-2026-07-24.md`.

Beyond the mechanical `--fix` output, two hand-fixes were needed (both
comment/docstring-only, no behavior change):

1. `state.clj` carried a stale decorative `;---- Layer 8b` banner
   immediately before `link-prs-from-dag` that `--fix` left untouched —
   the known "leftover banner at a now-contradictory position" tool
   limitation. `link-pr-dependencies` and `link-prs-from-dag` both landed
   at the same real stratum (7), so the stale banner (which also used a
   non-integer suffix that was never valid under rule 210 in the first
   place) was simply redundant noise between two same-layer defs. Deleted
   it, kept the plain `;; DAG-aware dependency linking` label comment
   above the function, and confirmed the file is stable across a third
   `--fix` pass.
2. `risk.clj`, `readiness.clj`, and `tiers.clj` each had a namespace
   docstring enumerating "Layer 0: ... Layer 1: ... Layer 2: ..." by hand.
   `--fix` recomputed real depth from the reference graph and two of the
   three no longer match: `readiness.clj` is now 4 real layers, not the
   documented 3, and `tiers.clj` is now 5, not the documented 2 (both
   previously under-counted by a heading structure that didn't track real
   dependency depth). Removed the stale per-layer breakdowns from all
   three docstrings rather than let them keep asserting counts that are
   already wrong today and would drift again at the next real edit.

## Motivation

`pr-train` carried 4 findings in the baseline: `SL003` on `interface.clj`
(13 distinct layers), `SL002` on `risk.clj` (a `Layer 0` heading reused
after `Layer 0`, i.e. the decorative-banner pattern), and `SL003` on
`schema.clj` (9 layers) and `state.clj` (11 layers). Zero `SL001`
findings — confirmed by a plain lint run before starting, per this
batch's instructions to stop and report rather than proceed if any
turned up. Nothing in the component references a same-file def defined
later at a lower layer, so this was safe to autofix mechanically with no
cycle/upward-reference risk to reason about first.

## Changes in Detail

`stratum-lint --fix` (pinned sha `14965e1ee1a175bd00f637d9a9d5f7d27e62b73f`,
read fresh from `tasks/stratum.clj` after resetting the branch onto
current `main`) rewrote all 19 Clojure files in the component — every
file, not just the 4 with findings, since `--fix` regenerates canonical
headings and `^{:stratum n}` tags on every def regardless of prior state.

- **src (7 files):** `core.clj`, `interface.clj`, `readiness.clj`,
  `risk.clj`, `schema.clj`, `state.clj`, `tiers.clj`
- **test (12 files):** `event_stream_test.clj`,
  `evidence_and_queries_test.clj`, `lifecycle_test.clj`,
  `merge_operations_test.clj`, `readiness_test.clj`, `risk_test.clj`,
  `state_computation_test.clj`, `state_creation_test.clj`,
  `state_machine_test.clj`, `state_transitions_test.clj`, `tiers_test.clj`,
  `train_control_test.clj`

`interface.clj` is the headline case: every one of its ~35 public defs is
a thin re-export delegating to `core`/`schema`/`state`/`risk`/`readiness`/`tiers`
— no same-file references at all — so the real reference graph puts
almost all of them at Layer 0. The one exception, `merge-all-ready`, calls
the same-file `get-ready-to-merge` and correctly lands at Layer 1. The
old headings (`Layer 0` through `Layer 12`) were pure per-section
decoration; the real structure is 2 layers, not 13.

`schema.clj` (9 → 6 real layers) and `state.clj` (11 → 8 real layers)
both remain over budget after the fix — see Testing Plan; these need an
actual namespace split (Wave 2), not another mechanical pass.

All test files only gained `^{:stratum n}` tags — every `deftest` in
every file is independent of every other same-file `deftest`, so all
land at Layer 0. No prior findings in test files (this component's
baseline findings were all in `src`).

## Testing Plan

- `--fix` run twice in a row (plus a third pass over `state.clj` alone
  after the hand-fix above); zero diff each time — idempotency confirmed
  directly.
- Read the full diff for all 19 changed files. Only heading text,
  `^{:stratum n}` metadata, and def/deftest reordering changed, plus the
  two hand-fixes described above. No same-line trailing comment was
  displaced onto the wrong def.
- `clj-kondo --lint components/pr-train`: 0 errors, 0 warnings.
- Ran all 12 test namespaces directly via `clojure -M:test`: 68 tests,
  337 assertions, 0 failures, 0 errors.
- Plain (non-`--fix`) lint after fixing: 4 `SL003` findings remain.
  - `schema.clj` (6 layers) and `state.clj` (8 layers): the **same**
    findings as the baseline, just with a smaller (and now accurate)
    layer count — both were already over budget before this PR under
    their old, inflated heading counts.
  - `readiness.clj` (4 layers) and `tiers.clj` (5 layers): **new**
    findings this fix surfaces. Neither was in the baseline — both had
    headings that under-counted real depth (`readiness.clj`'s 3 old
    headings implied 3 layers; `tiers.clj`'s 2 old headings implied 2),
    so the old, hand-written structure looked in-budget when the real
    same-file call graph was already deeper. Not a regression from this
    change — pre-existing depth the mechanical fix makes visible, same
    situation prior Wave 1 PRs (`bb-config`, `gate`) hit. All four are
    Wave 2 scope (real namespace split), not attempted here.
  - `interface.clj` (was 13 layers) and `risk.clj` (was the `SL002`
    banner-reuse finding) both fully resolved — 2 and 3 real layers
    respectively, both in budget.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — headings, metadata, comment positions, and two stale-docstring
corrections only. Pre-commit's `lint:stratum` autofixer keeps this
component clean going forward; the 4 `SL003` findings above stay
advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until
Wave 2 splits those namespaces.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for `schema.clj`, `state.clj`,
  `readiness.clj`, and `tiers.clj` in `components/pr-train`

## Checklist

- [x] Branch reset onto `origin/main` before starting (stale-pin bug from
      prior batches)
- [x] Zero `SL001` findings confirmed before running `--fix`
- [x] Idempotency verified directly (two full-component `--fix` passes,
      plus a third targeted pass after the hand-fix), zero diff each time
- [x] Full diff read for all 19 changed files; one stale-banner and three
      stale-docstring hand-fixes documented above, no other anomalies
- [x] `clj-kondo` clean across the whole component
- [x] Component tests pass (68 tests, 337 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: 4 `SL003` findings remain, 2 same as
      baseline (smaller count) and 2 newly surfaced — documented above as
      Wave 2 scope
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
