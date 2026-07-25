# fix: stratum-lint autofix for components/boundary (Wave 1)

## Overview

Runs `stratum-lint --fix` over the whole `boundary` component (`src` and
`test`) to replace decorative `Layer N` headings with headings that
reflect each file's real same-file reference graph, tagging every
`def`/`defn`/`deftest` with `^{:stratum n}` metadata. Purely mechanical: no
logic changes. Part of the per-component Wave 1 series from
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`boundary` carried exactly two findings in the baseline, both `SL003`
(over the 3-layer budget), zero `SL001` (upward-reference) findings:

```text
components/boundary/src/ai/miniforge/boundary/contract.clj:109:1: SL003 file uses 4 distinct layers (max 3)
components/boundary/src/ai/miniforge/boundary/core.clj:143:1: SL003 file uses 6 distinct layers (max 3)
```

No cycle/upward-call risk to reason about before running the mechanical
fixer.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/boundary
```

All 11 files in the component rewritten (3 `src`, 8 `test`).

`contract.clj`'s real reference graph collapses to 3 strata
(`exception-categories`/`CheckFn` at 0, `CapturedException`/
`valid-category?` at 1, the two `explain`/`valid?` helpers at 2) — the old
headings had over-counted at 4 by giving `CheckFn` its own `Layer 2` when
it has no same-file dependency beyond the `anomaly` require. This
`SL003` finding is now fully resolved.

`core.clj`'s real reference graph is 4 strata deep
(`category->anomaly-type`/`assert-known-category!`/`cause-message`/
`exception-class-name`/`safe-ex-data`/`safe-apply` at 0, through
`execute-with-exception-handling` at 3) — down from the 6 the old
headings claimed, but still one over budget. This is the same finding as
the baseline (a genuinely over-budget file), just re-counted: the old
headings over-reported the depth (6 vs. the real 4), they did not
under-report it. Deferred to Wave 2 (real namespace split), consistent
with how prior Wave 1 PRs (`bb-config`, `dag-primitives`) handled the
same situation.

One relocation worth flagging even though it isn't one of the two
documented tool-limitation patterns: `core.clj`'s trailing
`(assert (= contract/exception-categories ...))` compile-time invariant
— a bare top-level form, not a `def` — moved from directly under
`category->anomaly-type` to the very end of the file. `--fix` has no
stratum to assign a non-`def` form, so it appends it after the last
layer. Checked this doesn't change behavior: the assert still runs at
namespace load, after `category->anomaly-type` is already defined either
way, so the invariant check fires exactly as before. Left as-is.

## Testing Plan

1. Confirmed idempotency: ran `--fix` a second time — zero diff, zero
   "rewrote" output.
2. Read every changed file's full diff (all 11). Changes are heading
   text, `^{:stratum n}` metadata, and def/deftest reordering only. No
   same-line trailing comments existed in any of these files before the
   fix (checked via `grep` against the pre-fix content), so the
   comment-reattachment risk this wave watches for does not apply. No
   stale double-semicolon `;;---- Layer N: <description>` banners survived
   anywhere (`grep -rn ";;----"` over the component: zero matches).
3. `clj-kondo --lint components/boundary`: 0 errors. 1 warning
   (`Unresolved namespace clojure.string` in
   `exception_to_anomaly_test.clj`) — confirmed pre-existing on
   `origin/main` before this fix (same warning, different line number
   pre-reorder) and unrelated to stratum-lint.
4. Re-ran plain (non-`--fix`) `stratum-lint` over the component after the
   fix: one finding remains — `core.clj` `SL003`, 4 real layers (down from
   the baseline's reported 6, see Changes in Detail). Real over-budget
   file, Wave 2 scope, not fixed here.
5. Ran the component's test suite directly (`clojure -X:test
   cognitect.test-runner/test` from `components/boundary`): 47 tests, 73
   assertions, 0 failures, 0 errors.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; `core.clj`'s
remaining `SL003` stays advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at
commit time) until Wave 2 splits it.

## Related Issues/PRs

+ Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1 —
  mechanical relabeling via `--fix`, decorative-heading files only)
+ Follow-on: Wave 2 namespace split for
  `components/boundary/src/ai/miniforge/boundary/core.clj` (4 real
  layers, over the 3-layer budget)
+ Sibling Wave 1 PR docs:
  `docs/pull-requests/2026-07-25-fix-stratum-lint-wave1-bb-config.md`,
  `docs/pull-requests/2026-07-25-fix-stratum-lint-wave1-dag-primitives.md`,
  `docs/pull-requests/2026-07-25-fix-stratum-lint-wave1-tool.md`

## Checklist

+ [x] `--fix` run over the whole component (`src` and `test`)
+ [x] Idempotency verified (second `--fix` pass: zero diff)
+ [x] Diff reviewed file-by-file; mechanical-only (heading + metadata +
      reorder); no comment-attachment issue found (none existed to
      break); no stale decorative banners survived
+ [x] `clj-kondo` clean (0 errors; 1 pre-existing, unrelated warning)
+ [x] Plain lint re-run post-fix: `contract.clj`'s SL003 fully resolved;
      `core.clj`'s SL003 remains (4 real layers, re-counted down from 6,
      genuinely over budget — Wave 2 follow-up, not this PR)
+ [x] Component test suite: 47 tests, 73 assertions, 0 failures, 0 errors
+ [x] No `--no-verify`; pre-commit hook runs normally at commit time
