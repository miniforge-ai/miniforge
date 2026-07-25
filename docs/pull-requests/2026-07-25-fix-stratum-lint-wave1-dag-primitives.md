# fix: stratum-lint autofix for components/dag-primitives (Wave 1)

## Overview

Runs `stratum-lint --fix` over the whole `dag-primitives` component (`src`

+ `test`) to replace decorative `Layer N` headings with headings that
reflect each file's real same-file reference graph, tagging every
`def`/`defn`/`deftest` with `^{:stratum n}` metadata. Purely mechanical: no
logic changes. Part of the per-component Wave 1 series from
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`dag-primitives` carried exactly one finding in the baseline —
`components/dag_primitives/result.clj:99:1: SL003 file uses 4 distinct
layers (max 3)` — and zero `SL001` (upward-reference) findings, so no
cycle/upward-call risk to reason about before running the mechanical
fixer.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/dag-primitives
```

All 8 files in the component rewritten (4 `src`, 4 `test`) — `--fix`
normalizes files with zero prior findings too, adding `^{:stratum n}`
metadata as a one-time pass. Diff stat: 8 files changed, 92
insertions(+), 90 deletions(-).

`result.clj`'s real reference graph collapses to 2 strata (`ok`/`err`/etc.
at 0, everything downstream at 1) — well under budget. The old headings
(`Layer 0` through `Layer 3`) were decorative section banners
(Constructors/Extraction/Transforms/Combinators), not real dependency
depth. No other file changed layer count in a way that introduced a new
finding.

## Testing Plan

1. Confirmed idempotency: ran `--fix` a second time — zero diff, zero
   "rewrote" output.
2. Read every changed file's full diff (all 8; small component). Changes
   are heading text, `^{:stratum n}` metadata, and def
   reordering/re-grouping only. No same-line trailing comments existed in
   any of these files before the fix (checked via `grep` against the
   pre-fix `origin/main` content), so the comment-reattachment risk this
   wave watches for does not apply here — nothing to hand-fix.
3. `clj-kondo --lint components/dag-primitives`: 0 errors. 1 warning
   (deprecated-fn usage notice on `unwrap`, referenced from
   `interface.clj`) — confirmed pre-existing on `origin/main` before this
   fix and unrelated to stratum-lint; the project's own `clj-kondo` gate
   (`tasks/lint.clj`) already allows warnings and fails only on errors.
4. Re-ran plain (non-`--fix`) `stratum-lint` over the component after the
   fix: exit 0, zero findings remain. The SL003 finding is fully resolved
   — no Wave 2 namespace split needed for this component.
5. Ran the component's test suite directly (`clojure -M:test -m
   cognitect.test-runner` from `components/dag-primitives`): 30 tests, 55
   assertions, 0 failures, 0 errors.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata-only reorder. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward.

## Related Issues/PRs

+ Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1 —
  mechanical relabeling via `--fix`, decorative-heading files only)
+ Sibling Wave 1 PRs: `2026-07-24-fix-stratum-lint-wave1-compliance-scanner.md`,
  `-reliability.md`, `-gate.md`, `-decision.md`, `-adapter-claude-code.md`

## Checklist

+ [x] `--fix` run over the whole component (`src` + `test`)
+ [x] Idempotency verified (second `--fix` pass: zero diff)
+ [x] Diff reviewed file-by-file; mechanical-only (heading + metadata +
      reorder); no comment-attachment issue found (none existed to break)
+ [x] `clj-kondo` clean (0 errors; 1 pre-existing, unrelated warning)
+ [x] Plain lint re-run post-fix: zero findings (SL003 fully resolved, no
      Wave 2 follow-up needed for this component)
+ [x] Component test suite: 30 tests, 55 assertions, 0 failures, 0 errors
+ [x] No `--no-verify`; pre-commit hook runs normally at commit time
