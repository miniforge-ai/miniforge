<!--
  Title: Split policy-pack detection.clj — extract detection.custom-registry (2/2)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split detection.clj — extract detection.custom-registry (2/2)

## Overview

Slice 2 of the `detection.clj` rule-210 split (see PR 1/N,
`2026-08-11-refactor-split-policy-pack-detection-1.md`). Extracts
`ai.miniforge.policy-pack.detection.custom-registry`: the custom-fn
extension-point registry atom and the reflection helpers that decide
whether a candidate fn is a valid 2-arity detector —
`custom-fn-registry`, `declared-method?`, `reflectable-invoke?`,
`variadic-accepts-arity?`, `resolve-custom-fn`, `detector-predicate?`.
3 real layers, stratum-lint clean.

This is the **final slice** of this train: with these six functions
moved, `detection.clj` itself drops from 4 real layers to 3 — at the
rule 210 budget. Confirmed by hand-computing each remaining def's real
same-file dependency depth ahead of the move (cross-namespace calls no
longer count toward a file's own layer depth once the callee moves out),
then verifying with a scratch `stratum-lint --fix` dry-run before
committing.

## Motivation

Part of the stratum-lint rule-210 remediation program's Wave 2,
policy-pack batch 2. Continues from PR 1/N, which brought `detection.clj`
from 6 real layers to 4 by extracting the pattern-matching/plan-parsing
primitives into `detection.matching`.

## Changes in Detail

- New file `detection/custom_registry.clj`: the six functions named
  above, unchanged bodies. 3 layers (L0: `custom-fn-registry`,
  `declared-method?`, `reflectable-invoke?`; L1: `variadic-accepts-arity?`,
  `resolve-custom-fn`; L2: `detector-predicate?`).
  - `custom-fn-registry`, `resolve-custom-fn`, and `detector-predicate?`
    are now public (were `^:private`/`defn-` in the combined namespace)
    because `detection.clj` is now a cross-namespace caller of all
    three — the only visibility changes in this split.
- `detection.clj`: the six functions removed.
  `detect-custom`/`custom-fn-resolvable?`/`detect-violation` now call
  `custom-registry/resolve-custom-fn`, and
  `register-custom-fn!`/`unregister-custom-fn!` now call
  `custom-registry/custom-fn-registry` and
  `custom-registry/detector-predicate?` across the namespace boundary.
  `register-custom-fn!`, `unregister-custom-fn!`, `custom-fn-resolvable?`,
  and `detect-custom` themselves stay in `detection.clj`, unchanged in
  name/signature — they're the "use the registry" half of the custom-fn
  extension point; the "store/validate the registry" half moved.
  Ran `stratum-lint --fix` (folded into the pre-commit hook's
  `lint:stratum` step, same as PR 1/N) to renumber the remaining defs:
  4 → 3 real layers.
- Fan-in re-checked via the fully-qualified namespace grep
  (`ai\.miniforge\.policy-pack\.detection\b`, which also now matches the
  two sibling files' own namespace declarations as a harmless side
  effect of sharing the `detection.` prefix — not new external callers).
  All 6 moved symbols were `defn-`/`^:private` in the combined namespace,
  so **no external caller referenced them directly** — confirmed with
  `grep -rnE 'declared-method\?|reflectable-invoke\?|variadic-accepts-arity\?'`
  across `components`/`bases`/`projects`, which found only
  self-references inside the new file. None of the 13 files that
  reference `ai.miniforge.policy-pack.detection` needed call-site
  updates for this slice (unlike PR 1/N, which needed two).

This is pure code motion — no detection logic changed, def set unchanged
except location and the three visibility flips documented above.

## Testing Plan

- `stratum-lint` clean (exit 0) on all three files:
  `detection.clj` (now 3 real layers, down from 6 originally — **at
  budget**), `detection/matching.clj`, `detection/custom_registry.clj`.
- `clj-kondo` clean on both touched/added files.
- Directly ran every policy-pack test namespace that requires
  `ai.miniforge.policy-pack.detection` (from repo root, since
  `standard-packs-test` reads a repo-root-relative fixture path):
  - `ai.miniforge.policy-pack.detection-test`: 18 tests / 91 assertions
  - `ai.miniforge.policy-pack.ast-test`: 7 tests / 22 assertions
  - `ai.miniforge.policy-pack.intent-test`: 9 tests / 33 assertions
  - `ai.miniforge.policy-pack.builtin-detectors-test`: 1 test / 13 assertions
  - `ai.miniforge.policy-pack.compiler-test`: 19 tests / 64 assertions
  - `ai.miniforge.policy-pack.standard-packs-test`: 10 tests / 399 assertions
  - All 0 failures, 0 errors. (A first run from
    `components/policy-pack` as cwd showed one `standard-packs-test`
    failure — `.exists` false on a repo-root-relative fixture path; not
    a regression, reproduces identically on `main`, resolved by running
    from repo root as the test itself documents.)
  - The pre-commit hook's own `test:precommit`/`test:graalvm` passed on
    both commits (345 namespaces / 1301 assertions, then 8 GraalVM
    compat namespaces / 623 assertions).
- Adversarial self-review: diffed the top-level `defn`/`def` set before
  and after — exactly 6 relocated, 0 added/removed/altered in behavior;
  3 visibility flips (private → public), documented above; every other
  call site update is a namespace-qualification change only.

PR size: two commits, 62 and 86 reportable lines respectively (plus the
pre-commit hook's `lint:stratum` autofix re-staging, same pattern as PR
1/N — not a budget violation, `bb commit-budget` measured the pre-hook
staged diff). Both comfortably under the 600-line PR budget.
`MINIFORGE_STRATUM_BUDGET_MODE=warn` used on the second commit for the
plain-lint pre-commit gate, given `detection.clj` was still (briefly,
pre-autofix) over budget mid-commit.

## Deployment Plan

Merges to `main` as the final PR of this train.
`ai.miniforge.policy-pack.detection` is now stratum-lint compliant (3
real layers, budget 3), alongside its two new siblings
`detection.matching` and `detection.custom-registry`. No further slices
needed for this file.

## Related Issues/PRs

- Part 1/N of this train: `refactor(policy-pack): split detection.clj —
  extract detection.matching (1/N)`
- Precedent: [mdc_compiler.clj split, #1729-#1743](https://github.com/miniforge-ai/miniforge/pull/1729)
- Precedent: [workflow_runner.clj split, #1662-#1667](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2, policy-pack batch 2)

## Checklist

- [x] Fan-in confirmed via fully-qualified-namespace grep before starting;
      zero external call-site updates needed (all moved symbols were
      already private)
- [x] Pure code motion — no behavior change; 3 documented visibility flips
- [x] `clj-kondo` clean
- [x] Tests green across all 6 affected namespaces (run from repo root)
- [x] PR-diff and commit-diff budgets checked
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
- [x] `detection.clj` confirmed at 3 real layers (target reached) via
      `stratum-lint` post-commit
