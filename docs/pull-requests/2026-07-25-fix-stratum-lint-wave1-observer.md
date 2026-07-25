# fix: stratum-lint autofix for components/observer (Wave 1)

## Overview

Runs the pinned `stratum-lint --fix` over all 8 `.clj` files in
`components/observer` (5 src, 3 test) and commits the result. Regroups
each file's top-level defs under `;---- Layer N` headings that match the
tool's own same-file reference-graph inference, and adds `^{:stratum n}`
metadata to every def. No logic changes.

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

## Testing Plan

1. Ran `--fix` a second time over the already-fixed tree — zero diff,
   confirming idempotency.
2. Read every changed file's full diff (not `--stat`). Confirmed the
   only changes are heading text, `^{:stratum n}` metadata, and def
   reordering. Grepped the diff for the same-line trailing-comment
   pattern the tool is known to sometimes mis-attach across a move
   (`foo])  ; comment`); none present in any of the 8 files either
   before or after the fix, so no hand-fix was needed.
3. `clj-kondo --lint components/observer`: 0 errors, 0 warnings.
4. Plain (non-`--fix`) `stratum-lint` over `components/observer`
   afterward: 2 findings remain, both `SL003` (`alerts.clj` and
   `core.clj`, 5 real layers each) — expected Wave 2 work per above, not
   a defect in this PR.
5. Ran the component's test suite directly (`clojure -A:test`,
   requiring and running all 3 observer test namespaces): 24 tests, 105
   assertions, 0 failures, 0 errors.

## Deployment Plan

Merges to `main`. Pure source-formatting normalization (headings +
metadata) with no behavior change and no callers outside
`components/observer` — nothing to roll out or monitor.

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
- [x] Full diff read for all 8 changed files; confirmed mechanical
      (headings + `^{:stratum n}` metadata + reordering only)
- [x] Checked for the known same-line trailing-comment mis-attachment
      failure mode; none present, no hand-fix required
- [x] `clj-kondo`: 0 errors, 0 warnings
- [x] Component test suite green: 24/24, 105 assertions, 0 failures,
      0 errors
- [x] Post-fix plain lint: `interface.clj` clean; `SL003` remainder on
      `alerts.clj`/`core.clj` documented as Wave 2 scope, not this PR's
      to fix
