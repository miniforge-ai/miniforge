<!--
  Title: Split policy-pack mdc_compiler.clj — extract frontmatter (2/6)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split mdc_compiler.clj — extract frontmatter (2/6)

## Overview

Slice 2 of the 6-PR split train for
`components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj`
(rule 210, SL003 — 9 real layers, max 3; slice 1 was #1729). This
slice extracts `ai.miniforge.policy-pack.mdc-compiler.frontmatter`:
the frontmatter line-grammar — `split-frontmatter`, `parse-list-item`,
`parse-kv-line`, `process-frontmatter-line`, `parse-frontmatter` —
which requires slice 1's `frontmatter-values` for `strip-quotes` and
`parse-frontmatter-value`.

## Motivation

Re-confirmed zero fan-in on `mdc-compiler` before starting (no other
namespace requires it repo-wide), and re-verified the branch was
rebased onto the latest `origin/main` (which now includes #1729)
before making changes.

## Changes in Detail

- New file `mdc_compiler/frontmatter.clj`: the frontmatter
  line-grammar functions, 3 real layers (0-2), stratum-lint clean on
  its own.
- `mdc_compiler.clj`: the five functions removed; `parse-mdc` now
  calls `frontmatter/split-frontmatter` and
  `frontmatter/parse-frontmatter`. Ran `stratum-lint --fix` to
  renumber headings/`:stratum` metadata and updated the namespace
  docstring's layer summary.
- **Notable finding**: the file still measures 7 real layers after
  this slice, unchanged from slice 1. The frontmatter chain was never
  the sole bottleneck — `parse-mdc`'s stratum dropped to 0 (all its
  remaining dependencies are now external), but the file's overall
  layer count is set by an independent chain:
  `condense-to-length → extract-agent-behavior → mdc->rule →
  compile-standards-pack`. Slices 3-4 (the condense/agent-behavior
  extraction) target that chain directly and are expected to bring
  the real layer count down.
- `mdc_compiler_test.clj`: added a require for the new
  `frontmatter` namespace; `split-frontmatter-test`'s 6 call sites
  now call `frontmatter/split-frontmatter` instead of
  `sut/split-frontmatter`. No other test changes needed —
  `parse-list-item`/`parse-kv-line`/`process-frontmatter-line`/
  `parse-frontmatter` had no direct test coverage (exercised only
  indirectly through `parse-mdc`, whose tests are unaffected).

The parent namespace stays over budget (7 real layers) until the
remaining slices land; the commit removing the functions used
`MINIFORGE_STRATUM_BUDGET_MODE=warn` to get past the pre-commit gate's
plain-lint check on the intermediate state, same convention as #1662
and #1729.

## Testing Plan

1. `clj-kondo` clean on all three touched files.
2. stratum-lint: `frontmatter.clj` passes SL003 outright;
   `mdc_compiler.clj` intentionally still over budget (7 layers,
   unchanged from slice 1) — expected, see Motivation above.
3. `ai.miniforge.policy-pack.mdc-compiler-test`: 26 tests / 159
   assertions, 0 failures, 0 errors — unchanged from main.
4. Full pre-commit suite (`poly:check`, lint, smoke tests, GraalVM)
   passed on both commits.
5. Adversarial self-review: diffed the full top-level `defn`/`def` set
   before and after — exactly 5 relocated, 0 added/removed/altered in
   behavior; `parse-mdc`'s body is unchanged except for the two
   qualified calls, every other def's body is byte-identical.

PR size: reportable lines were checked per-commit (74 for the new
file, 132 for the removal/test-update commit), both under the
200-line commit budget; combined well under the 600-line PR budget.

## Deployment Plan

Merges to `main` as part of the ongoing 6-PR train. The next slice
rebases onto the updated `main` after this one merges.

## Related Issues/PRs

- Slice 1: [#1729](https://github.com/miniforge-ai/miniforge/pull/1729)
- Precedent: [dag-orchestrator split, #1485](https://github.com/miniforge-ai/miniforge/pull/1485)
- Precedent: [workflow-runner split, #1662](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2)

## Checklist

- [x] Zero fan-in re-confirmed before starting
- [x] Branch rebased onto latest `origin/main` (includes #1729)
- [x] Pure code motion — no behavior change, no logic altered
- [x] `clj-kondo` clean
- [x] Tests green (26/26, 159 assertions)
- [x] Commit-diff budgets checked (74 and 132, both ≤200); PR total
      well under 600
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
