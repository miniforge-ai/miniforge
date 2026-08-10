<!--
  Title: Split policy-pack mdc_compiler.clj — extract frontmatter-values (1/6)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split mdc_compiler.clj — extract frontmatter-values (1/6)

## Overview

`components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj`
trips stratum-lint SL003: 9 distinct real layers, max 3 (rule 210,
`standards/miniforge`). This is slice 1 of a 6-PR split following the
dag-orchestrator (#1485) and workflow-runner (#1662) precedent: one
cohesive concern per namespace, each new file within the 3-layer
budget, mechanical moves only.

This slice extracts `ai.miniforge.policy-pack.mdc-compiler.frontmatter-values`:
quote-stripping and the MDC frontmatter scalar-value grammar
(booleans, quoted strings, inline arrays, bare strings) — `strip-quotes`,
`parse-inline-array`, `parse-frontmatter-value`.

## Motivation

Confirmed zero fan-in repo-wide before starting
(`grep -rl "policy[-_]pack\.mdc_compiler\b"` across
`components`/`bases`/`projects`) — no other namespace requires
`mdc-compiler`, so this split is purely an internal reorganization; no
public API changes anywhere in the repo.

## Changes in Detail

- New file `mdc_compiler/frontmatter_values.clj`: `strip-quotes`
  (now public — needed across the namespace boundary),
  `parse-inline-array` (private), `parse-frontmatter-value` (public).
  3 real layers (0-2), stratum-lint clean.
- `mdc_compiler.clj`: the three functions removed; `parse-list-item`
  and `parse-kv-line` now call `frontmatter-values/strip-quotes` and
  `frontmatter-values/parse-frontmatter-value` respectively. Ran
  `stratum-lint --fix` to renumber the remaining defs' `;--- Layer N`
  headings and `^{:stratum n}` metadata for the new real-layer count
  (9 → 7) and updated the namespace docstring's layer summary to
  match.
- No test file changes: neither `strip-quotes`, `parse-inline-array`,
  nor `parse-frontmatter-value` had direct test coverage in
  `mdc_compiler_test.clj` (all three were exercised only indirectly
  through `parse-mdc`/`mdc->rule`, whose tests are unaffected).

The parent namespace stays over budget (7 real layers) until the
remaining slices land; the commit doing the removal used
`MINIFORGE_STRATUM_BUDGET_MODE=warn` to get past the pre-commit gate's
plain-lint check on the intermediate state, same convention as #1662.
The final slice in this train brings it to 3 layers.

## Testing Plan

1. `clj-kondo` clean on both touched files.
2. stratum-lint: `frontmatter_values.clj` passes SL003 outright;
   `mdc_compiler.clj` intentionally still over budget (7 layers) until
   the rest of the train lands — expected and tracked, not a defect.
3. `ai.miniforge.policy-pack.mdc-compiler-test`: 26 tests / 159
   assertions, 0 failures, 0 errors — unchanged from main.
4. Full pre-commit suite (`poly:check`, lint, smoke tests, GraalVM)
   passed on both commits.
5. Adversarial self-review: diffed the full top-level `defn`/`def` set
   before and after — exactly 3 relocated, 0 added/removed/altered in
   behavior; every other def's body is byte-identical, only its
   `:stratum` tag and heading section moved.

PR size: 213 reportable lines (156 raw insertions / 122 raw
deletions across 2 files), under the 600-line budget — no override
needed.

## Deployment Plan

Merges to `main` as part of an ongoing 6-PR train. Each subsequent PR
rebases onto the updated `main` after the prior one merges.

## Related Issues/PRs

- Precedent: [dag-orchestrator split, #1485](https://github.com/miniforge-ai/miniforge/pull/1485)
- Precedent: [workflow-runner split, #1662](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2)

## Checklist

- [x] Zero fan-in confirmed via repo-wide grep before starting
- [x] Pure code motion — no behavior change, no logic altered
- [x] `clj-kondo` clean
- [x] Tests green (26/26, 159 assertions)
- [x] PR-diff and commit-diff budgets checked (213/600, both commits ≤200)
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
