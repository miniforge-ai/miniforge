# fix: stratum-lint autofix for components/response-chain (Wave 1)

## Overview

Mechanical `stratum-lint --fix` pass over `components/response-chain`
(`src` + `test`) — one component from
`work/stratum-lint-baseline-2026-07-24.md`'s Wave 1 batch 4 — to replace
decorative `Layer N` headings with real ones derived from each file's
actual same-file reference graph. No logic changes: every diff is
heading text, `^{:stratum n}` metadata, and def/deftest reordering.

## Motivation

`response-chain` carried 3 findings under the baseline's cargo-cult
diagnosis: one `SL002` in `core.clj` (a repeated `Layer 0` heading —
`build-step` and the operation-coercion sentinel both banner'd as
"Layer 0" even though the file's later defs genuinely build on them),
and `SL003` in both `core.clj` and `interface.clj` (5 distinct layers
against the 3-layer budget). Zero `SL001` findings for the component —
matches Wave 1's selection criteria: no upward-reference/cycle risk to
reason about before running the mechanical fixer.

## Changes in Detail

Ran, over the whole component, at the current stratum-lint pin
(`14965e1ee1a175bd00f637d9a9d5f7d27e62b73f`, already current — no bump
needed this batch):

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/response-chain
```

13 files rewritten (`--fix` normalizes every already-annotated file in
the component, not just the 2 with findings): `contract.clj`, `core.clj`,
`interface.clj` (`src`), and all 10 test files.

- **`core.clj`**: the real reference graph collapses `succeeded?`,
  `steps`, `last-response`, and `last-anomaly` (previously banner'd as
  Layer 3/Layer 4 "Predicate"/"Accessors" sections placed after
  `append-step`) down to `Layer 0` — none of them actually reference
  anything else in the file, they only read keys off the chain map
  directly. `recompute-succeeded?` and `invalid-operation-sentinel` join
  them at `Layer 0`, resolving the `SL002` duplicate-heading finding.
  `coerce-operation`, `conj-step`, and `last-successful-or` land at
  `Layer 1` (each references a `Layer 0` def); `create-chain` at `Layer 2`;
  `guarded-append` at `Layer 3`; `append-step` at `Layer 4`. Still 5 real
  layers total — `SL003` remains, see below.
- **`interface.clj`**: every function is a pure pass-through to `core/*`
  with no same-file references between them, so the real depth is a
  single layer. All 8 defs (the two schema re-exports plus 6 functions)
  land at `Layer 0`. The old headings claimed 5 layers (`Layer 0`–`Layer 4`,
  one per function) that never existed in the same-file graph — `SL003`
  here is fully resolved, not just relabeled.
- **`contract.clj`** (no prior findings): `Step` at `Layer 0`;
  `valid-step?`/`explain-step` at `Layer 1` (reference `Step` only);
  `valid-chain?`/`explain-chain` moved from the old "Layer 2 Validation
  helpers" grouping to `Layer 2` proper, now correctly separated from
  `valid-step?`/`explain-step` since they reference `Chain` (`Layer 1`),
  one level deeper. Stays within budget (3 layers), still 0 findings.
- **Test files** (all 10): each file's `deftest` forms have no same-file
  references to each other, so every one collapses to a single
  `Layer 0` heading — mechanical, no reordering of assertions within a
  test.

No stale double-semicolon `;;---- Layer N` banners or same-line trailing
comments were present in this component to begin with, so neither of the
two known tool-limitation patterns applied — confirmed by reading every
changed file's full diff. No `defmethod`s in this component either.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's 3 findings exactly (1 `SL002` + 2 `SL003`), 0 `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 13 changed files. No misplaced trailing
   comment, no stale non-standard heading banner, no `defmethod` stratum
   issue.
4. `clj-kondo --lint components/response-chain`: 0 errors, 0 warnings.
5. Ran all 10 test namespaces via `clojure -M:test -m cognitect.test-runner`:
   54 tests, 121 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear. `interface.clj`'s `SL003` is fully resolved (real depth is 1
   layer, not 5 — the old heading over-counted it). `core.clj`'s `SL003`
   remains: still 5 real layers against the 3-layer budget — the same
   finding as before, not newly surfaced, since `core.clj`'s old headings
   already reflected the real depth (aside from the duplicate `Layer 0`
   this fix resolved). Deferred to Wave 2 (real namespace split).

## Deployment Plan

Merges to `main` like any other component change. Comment/metadata/order
only — no runtime behavior change. Pre-commit's `lint:stratum` autofixer
keeps this component clean going forward; `core.clj`'s `SL003` stays
advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until
Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/response-chain/src/ai/miniforge/response_chain/core.clj`
  (5 real layers, over the 3-layer budget)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 13 changed files; mechanical-only
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component tests pass (54 tests, 121 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: `SL003` remains only on `core.clj`
      (pre-existing, tracked as Wave 2); `interface.clj`'s `SL003` fully
      resolved
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
