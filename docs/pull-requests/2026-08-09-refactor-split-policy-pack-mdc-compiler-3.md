<!--
  Title: Split policy-pack mdc_compiler.clj — extract condense (3/6)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split mdc_compiler.clj — extract condense (3/6)

## Overview

Slice 3 of the 6-PR split train for
`components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj`
(rule 210, SL003 — 9 real layers, max 3; slices 1-2 were #1729/#1732).
This slice extracts `ai.miniforge.policy-pack.mdc-compiler.condense`:
the text-condensation chain — `keep-whole-sentences`,
`bullet-line-pattern`, `bullet-line?`, `condense-prose`,
`condense-bullets`.

## Motivation

Re-confirmed zero fan-in and rebased onto latest `origin/main` before
starting, as with slices 1-2.

**Notable finding**: this chain, not the frontmatter grammar slices
1-2 targeted, turned out to be the parent namespace's real bottleneck.
`condense-to-length` alone accounted for 4 of the file's 7 real
layers — slices 1-2 correctly reduced the file's line count and
frontmatter-specific depth, but the overall SL003 layer count stayed
at 7 through both (see their PR docs) because an independent chain
was setting the ceiling. Extracting it here drops the file to 5 real
layers, confirming the diagnosis.

## Changes in Detail

- New file `mdc_compiler/condense.clj`: the condensation chain, 3 real
  layers (0-2), stratum-lint clean on its own.
- `mdc_compiler.clj`: the five functions removed; `condense-to-length`
  now calls `condense/bullet-line?`, `condense/condense-bullets`, and
  `condense/condense-prose`. Ran `stratum-lint --fix` to renumber
  headings/`:stratum` metadata and updated the namespace docstring's
  layer summary — the surviving critical path is now the Dewey-range
  chain (`find-dewey-range` → `dewey->phases`/`category-id`/
  `category-label` → `build-categories`/`mdc->rule` →
  `compile-standards-pack`), which the remaining slices target.
- No test file changes: none of the five extracted functions had
  direct test coverage in `mdc_compiler_test.clj` (exercised only
  indirectly through `extract-agent-behavior`, whose tests are
  unaffected).

The parent namespace stays over budget (5 real layers) until the
remaining slices land; the removal commit used
`MINIFORGE_STRATUM_BUDGET_MODE=warn`, same convention as prior slices.

## Testing Plan

1. `clj-kondo` clean on both touched files.
2. stratum-lint: `condense.clj` passes SL003 outright; `mdc_compiler.clj`
   intentionally still over budget (5 layers, down from 7) — expected,
   see Motivation above.
3. `ai.miniforge.policy-pack.mdc-compiler-test`: 26 tests / 159
   assertions, 0 failures, 0 errors — unchanged from main.
4. Full pre-commit suite (`poly:check`, lint, smoke tests, GraalVM)
   passed on both commits.
5. Adversarial self-review: diffed the full top-level `defn`/`def` set
   before and after — exactly 5 relocated, 0 added/removed/altered in
   behavior; `condense-to-length`'s body is unchanged except for the
   three qualified calls.

PR size: reportable lines checked per-commit (60 for the new file, 143
for the removal commit), both under the 200-line commit budget;
combined well under the 600-line PR budget.

## Deployment Plan

Merges to `main` as part of the ongoing 6-PR train. The next slice
rebases onto the updated `main` after this one merges.

## Related Issues/PRs

- Slice 1: [#1729](https://github.com/miniforge-ai/miniforge/pull/1729)
- Slice 2: [#1732](https://github.com/miniforge-ai/miniforge/pull/1732)
- Precedent: [dag-orchestrator split, #1485](https://github.com/miniforge-ai/miniforge/pull/1485)
- Precedent: [workflow-runner split, #1662](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2)

## Checklist

- [x] Zero fan-in re-confirmed before starting
- [x] Branch rebased onto latest `origin/main`
- [x] Pure code motion — no behavior change, no logic altered
- [x] `clj-kondo` clean
- [x] Tests green (26/26, 159 assertions)
- [x] Commit-diff budgets checked (60 and 143, both ≤200); PR total
      well under 600
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
