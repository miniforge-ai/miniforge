# fix: stratum-lint autofix for components/observer (Wave 1)

## Overview

Runs the pinned `stratum-lint --fix` over all 8 `.clj` files in
`components/observer` (5 src, 3 test) and commits the result. Regroups
each file's top-level defs under the tool's canonical heading form —
a long-dash comment line ending in `Layer N`
(`;------------------------------------------------------------------------------ Layer N`)
— that matches the tool's own same-file reference-graph inference, and
adds `^{:stratum n}` metadata to every def. No logic changes.

## Motivation

Wave 1 of `work/stratum-lint-baseline-2026-07-24.md`: the full-tree
baseline reported exactly one finding for `observer` — `SL003` in
`interface.clj` (6 distinct layers, over the 3-layer budget) — and zero
`SL001` (no upward-reference/cycle risk to reason about first), the
profile the Wave 1 plan targets for mechanical autofix.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/observer
```

All 8 files rewritten. `interface.clj`'s pre-existing `SL003` is
resolved: it's a pure re-export file (every def just delegates to
`core`/`protocol`/`alerts`/`alert-subscriber`), so the real reference
graph is 1 layer, not the 6 decorative ones on disk before the fix.

The other 4 files touched — `alerts.clj`, `core.clj`, `protocol.clj`,
`alert_subscriber.clj` — carried **zero** `Layer N` headings before this
fix, which is the tool's documented blind spot (a headingless file is
silently skipped, not passed) called out in the baseline doc. `--fix`
added real headings to them for the first time, which surfaced two
pre-existing over-budget files that the baseline run never saw:
`alerts.clj` and `core.clj`, both 5 real layers. Neither is introduced or
worsened by this PR — both are Wave 2 scope (real namespace split).
`protocol.clj` and `alert_subscriber.clj` came in under budget (1 and 3
layers respectively) and report clean.

Three test files were also normalized the same way (headings +
metadata only; `deftest` reordering, no assertion changes).

### Review follow-up (not part of the mechanical fix)

Automated review flagged two items on `interface_test.clj`, addressed
separately from the stratum-lint mechanics above:

- `run-all-observer-tests` was a no-op (`(is true "...")`) asserting
  nothing about Observer behavior, preceded by a stale "WorkflowObserver
  Integration Tests" header with no tests under it. Checked git history
  (`git log --follow`) before touching it: the real WorkflowObserver
  protocol tests this header once introduced were relocated to
  `projects/miniforge/test/ai/miniforge/observer/interface_integration_test.clj`
  in commit `76af82fbd` ("refactor: split unit and integration tests"),
  and still live there today with real assertions (confirmed by running
  that namespace directly: 1 test, 7 assertions, 0 failures/errors). The
  no-op `deftest` itself was a no-op since the component's original
  commit (`6231b5e2e`) — never asserted anything beyond `(is true ...)`.
  Removed both the stale header and the no-op test; no coverage lost,
  since none of it was providing any.
- The Overview's mention of `;---- Layer N` headings was shorthand that
  didn't match the actual committed heading syntax (the long-dash
  canonical form, `;------------------------------------------------------------------------------ Layer N`,
  same as every other Wave 1 PR's real output). Fixed the wording here.

A second review comment reported a real bug in `core.clj`'s
`analyze-trends`, surfaced (not introduced) by the mechanical relocation
this PR performs on that function. `first-durations`/`second-durations`/
`first-costs`/`second-costs` were built with plain `map` over
`get-in` on `[:metrics :duration-ms]`/`[:metrics :cost-usd]`; any entry
whose `:metrics`
map is missing that key (e.g. an `:initial-state` workflow with no
recorded duration/cost yet) produces a `nil` in the sequence. The `(seq
...)` guard only checks non-emptiness, not absence of nils, so a single
such entry reaches `(reduce + ...)` and throws. Fixed by wrapping each of
the four `map` calls in `remove nil?` before the guard, so an
all-or-partially-nil sequence collapses to empty and correctly falls
through to the existing "insufficient data" branch instead of crashing.
Added `analyze-trends-missing-metric-fields-test`, which collects four
workflows with an empty `:metrics` map (no `:duration-ms`/`:cost-usd`)
and asserts `analyze-metrics :trends` returns nil trend data and the
"Insufficient data" summary rather than throwing. Verified the test
actually catches the bug: reverted the `core.clj` fix, reran, got 1
error (the reproduced crash); restored the fix, reran, 0 errors. That
new test's fixture originally generated two different UUIDs for the
same workflow (one passed as `collect-workflow-metrics`' id argument,
a different one embedded in `:workflow/id`), a review comment on this
fixture's realism — fixed to generate one UUID and reuse it in both
places.

A third review comment found a second real bug in the same function:
`analyze-trends`' summary string divides by each trend's `:first-avg` to
compute percent change, with no guard for a zero baseline — a workflow
whose first-half average duration or cost is genuinely `0.0` would
produce "Infinity% change" or "NaN% change" in the summary instead of a
number. Added `pct-change` (Layer 0, alongside `calculate-percentile`):
returns `0.0` when `before` is zero rather than dividing by it. Both
`analyze-trends` call sites now go through it.

## Testing Plan

1. Ran `--fix` a second time over the already-fixed tree — zero diff,
   confirming idempotency.
2. Read every changed file's full diff (not `--stat`). The mechanical
   `--fix` pass itself is heading text, `^{:stratum n}` metadata, and def
   reordering only. Grepped the diff for the same-line trailing-comment
   pattern the tool is known to sometimes mis-attach across a move
   (`foo])  ; comment`); none present in any of the 8 files either
   before or after the fix, so no hand-fix was needed. Review follow-up
   passes on top of that mechanical fix introduced two real behavior
   changes — see "Review follow-up" below and the checklist — so this PR
   as a whole is not purely mechanical, only its initial commit was.
3. `clj-kondo --lint components/observer`: 0 errors, 0 warnings.
4. Plain (non-`--fix`) `stratum-lint` over `components/observer`
   afterward: 2 findings remain, both `SL003` (`alerts.clj` and
   `core.clj`, 5 real layers each) — expected Wave 2 work per above, not
   a defect in this PR.
5. Ran the component's test suite directly (`clojure -A:test`,
   requiring and running all 3 observer test namespaces): 24 tests, 108
   assertions, 0 failures, 0 errors (post review-follow-up removal of
   the one no-op test, plus the new `analyze-trends` nil-safety test).
   Also ran the relocated integration test (`projects/miniforge`,
   `observer.interface-integration-test`) directly to confirm the real
   WorkflowObserver protocol coverage is intact: 1 test, 7 assertions,
   0 failures, 0 errors.
6. Re-ran `--fix` after the `analyze-trends` fix and new test — the new
   test relocated to its inferred stratum (a leaf, no cross-refs), no
   other file changed; a second `--fix` run after that was zero diff.
   `clj-kondo` and the plain lint re-run both unchanged (0/0; same 2
   pre-existing `SL003` findings).

## Deployment Plan

Merges to `main`. The stratum-lint pass itself is source-formatting
normalization only, but this PR also carries two real behavior changes
from review follow-up (see below): `analyze-trends` no longer throws on
metrics missing `:duration-ms`/`:cost-usd`, and no longer reports
Infinity/NaN percent-change when a trend's first-half average is zero.
Both are bug fixes with no external contract change — `analyze-trends`'
documented return shape is unchanged, callers see either correct numbers
or the existing "Insufficient data" fallback instead of an exception or
a nonsensical percentage. No callers outside `components/observer` —
nothing to roll out or monitor beyond normal merge-to-main.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Depends on: #1459 (stratum-lint pre-commit autofix wiring, already
  merged) — reuses the same pinned sha
- Enables: Wave 2 (real namespace split) for `alerts.clj` (5 real
  layers) and `core.clj` (5 real layers), both now confirmed via `SL003`
  rather than assumed from the pre-fix baseline, which never saw either
  file (no headings, silently skipped).

## Checklist

- [x] Idempotency verified: second `--fix` run produced zero diff
- [x] Full diff read for all 8 changed files; the initial mechanical
      commit is headings + `^{:stratum n}` metadata + reordering only —
      later review-follow-up commits add the two real bug fixes below
- [x] Checked for the known same-line trailing-comment mis-attachment
      failure mode; none present, no hand-fix required
- [x] `clj-kondo`: 0 errors, 0 warnings
- [x] Component test suite green: 24/24, 108 assertions, 0 failures,
      0 errors; relocated integration test also verified (1/1, 7
      assertions)
- [x] Post-fix plain lint: `interface.clj` clean; `SL003` remainder on
      `alerts.clj`/`core.clj` documented as Wave 2 scope, not this PR's
      to fix
- [x] Review follow-up: stale header + no-op test removed from
      `interface_test.clj` after confirming via git history that no
      coverage was lost; doc wording for the heading syntax corrected
- [x] Review follow-up: `analyze-trends` nil-safety bug fixed (filter
      nils before `reduce +`); new regression test verified to actually
      catch the bug (fails without the fix, passes with it)
- [x] Review follow-up: that regression test's fixture generated two
      different UUIDs for what should be the same workflow id — fixed
      to reuse one
- [x] Review follow-up: `analyze-trends` zero-baseline division bug
      fixed (`pct-change` helper, returns 0.0 instead of Infinity/NaN)
