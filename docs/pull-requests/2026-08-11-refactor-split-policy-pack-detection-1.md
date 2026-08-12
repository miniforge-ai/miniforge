<!--
  Title: Split policy-pack detection.clj — extract detection.matching (1/N)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split detection.clj — extract detection.matching (1/N)

## Overview

`components/policy-pack/src/ai/miniforge/policy_pack/detection.clj` trips
stratum-lint SL003: 6 distinct real layers, max 3 (rule 210,
`standards/miniforge`). This is slice 1 of a multi-PR split following the
mdc_compiler.clj train (miniforge#1729-#1743) and the workflow_runner.clj
train (miniforge#1662-#1667): one cohesive concern per namespace, each new
file within the 3-layer budget, mechanical moves only.

This slice extracts `ai.miniforge.policy-pack.detection.matching`: pattern
coercion, detection-config pattern extraction, terraform plan-output
parsing, and the line/multiline pattern-match helpers — `ensure-pattern`,
`extract-patterns`, `parse-plan-resources`, `find-matches`,
`any-pattern-matches-multiline?`, `plan-resource-counts`,
`any-pattern-matches?`. 3 real layers, stratum-lint clean.

## Motivation

Part of the stratum-lint rule-210 remediation program's Wave 2, policy-pack
batch 2. `detection.clj` (823 lines) is the largest and most-depended-on
file in this batch: 13 files repo-wide reference
`ai.miniforge.policy-pack.detection` (confirmed via
`grep -rlE "ai\.miniforge\.policy-pack\.detection\b"`, which catches every
caller regardless of require `:as` alias — a prior PR in this batch was
broken by a symbol-prefix grep that missed an aliased call site).

## Changes in Detail

- New file `detection/matching.clj`: the 7 pattern/plan-parsing functions
  named above, unchanged bodies. 3 layers (L0: `ensure-pattern`,
  `extract-patterns`, `parse-plan-resources`; L1: `find-matches`,
  `any-pattern-matches-multiline?`, `plan-resource-counts`; L2:
  `any-pattern-matches?`).
- `detection.clj`: the 7 functions removed. `detect-ast-analysis`,
  `detect-content-scan`, `detect-diff-analysis`, and `detect-plan-output`
  now call `matching/extract-patterns`, `matching/any-pattern-matches?`,
  `matching/any-pattern-matches-multiline?`, `matching/parse-plan-resources`,
  and `matching/ensure-pattern` across the namespace boundary instead of
  same-file calls. Ran `stratum-lint --fix` (folded into the pre-commit
  hook's `lint:stratum` step) to renumber the remaining defs' `;--- Layer N`
  headings and `^{:stratum n}` metadata: 6 → 4 real layers. Still over the
  3-layer budget — the rest of this train brings it down further.
- Two external callers updated, found via the fully-qualified namespace
  grep (not a symbol-prefix guess, per the fan-in bug that hit two earlier
  PRs in this batch):
  - `intent.clj`'s `parse-terraform-plan-counts` now delegates to
    `matching/plan-resource-counts` instead of
    `detection/plan-resource-counts`; the `detection` require is replaced
    with a `detection.matching` require (it had no other use of
    `detection/*`).
  - `ast_test.clj`'s `plan-resource-counts-test` now calls
    `matching/plan-resource-counts`. The same file's
    `state-comparison-detection-test` still calls
    `detection/detect-state-comparison` — that detector hasn't moved — so
    the file now requires both `detection` and `detection.matching`.
- No other repo-wide caller referenced the seven moved functions directly
  (confirmed against all 13 files found by the namespace grep — the other
  11 use `detection/register-custom-fn!`, `detection/detect-violation`,
  `detection/check-rules`, `detection/classify-violations`,
  `detection/violation->error`, `detection/violation->warning`, and
  similar, none of which moved in this slice).

This is pure code motion — no detection logic changed, def set unchanged
except location.

## Testing Plan

- `stratum-lint` clean on `detection/matching.clj` (exit 0).
- `stratum-lint` on `detection.clj`: SL003 4 real layers (down from 6),
  expected and tracked — not yet compliant, more slices follow.
- `clj-kondo` clean on all four touched/added files.
- Directly ran the three affected test namespaces (`bb test`'s
  change-scope plus `test:precommit`/`test:graalvm` both ran clean via the
  pre-commit hook; the direct runs below re-confirm the same after the
  hook's stratum-lint autofix landed):
  - `ai.miniforge.policy-pack.detection-test`: 18 tests / 91 assertions, 0
    failures.
  - `ai.miniforge.policy-pack.ast-test`: 7 tests / 22 assertions, 0
    failures.
  - `ai.miniforge.policy-pack.intent-test`: 9 tests / 33 assertions, 0
    failures.
- Adversarial self-review: diffed the top-level `defn`/`def` set before and
  after — exactly 7 relocated, 0 added/removed/altered in behavior; every
  call site update is a namespace-qualification change only.

PR size: two commits, 143 and 167 reportable lines respectively (the
second commit's actually-committed diff is larger after the pre-commit
hook's `lint:stratum` autofix re-stages the renumbered file — expected,
not a budget violation, since `bb commit-budget` measured the pre-hook
staged diff). Both under the 600-line PR budget; no override needed.
`MINIFORGE_STRATUM_BUDGET_MODE=warn` used on the second commit for the
plain-lint pre-commit gate, given the file is intentionally still over
budget mid-train — same convention as the mdc_compiler.clj train.

## Deployment Plan

Merges to `main` as part of an ongoing multi-PR train. The next slice
extracts the custom-fn registry primitives (`custom-fn-registry`,
`declared-method?`, `reflectable-invoke?`, `variadic-accepts-arity?`,
`resolve-custom-fn`, `unregister-custom-fn!`, `detector-predicate?`) into
`detection.custom-registry`. Each subsequent PR rebases onto the updated
`main` after the prior one merges.

## Related Issues/PRs

- Precedent: [mdc_compiler.clj split, #1729-#1743](https://github.com/miniforge-ai/miniforge/pull/1729)
- Precedent: [workflow_runner.clj split, #1662-#1667](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2, policy-pack batch 2)

## Checklist

- [x] Fan-in confirmed via fully-qualified-namespace grep before starting
      (13 files; two required call-site updates, both handled here)
- [x] Pure code motion — no behavior change, no logic altered
- [x] `clj-kondo` clean
- [x] Tests green (detection-test 18/91, ast-test 7/22, intent-test 9/33)
- [x] PR-diff and commit-diff budgets checked
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state (6 → 4 layers)
