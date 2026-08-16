<!--
  Title: Split policy-pack mdc_compiler.clj — extract rule-config, final slice (6/6)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split mdc_compiler.clj — extract rule-config, final slice (6/6)

## Overview

Final slice (6/6) of the split train for
`components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj`
(rule 210, SL003 — originally 9 real layers, max 3; slices 1-5 were
`#1729`/`#1732`/`#1733`/`#1740`/`#1742`). This slice extracts
`ai.miniforge.policy-pack.mdc-compiler.rule-config`: the rule-level
config builders — `build-exclude-context`, `build-detection-config`,
`valid-enforcement-actions`, `build-remediation-config`.

## Motivation

Re-confirmed zero external fan-in on all four moved items and rebased
onto latest `origin/main` before starting.

This was the second of the two independent chains feeding `mdc->rule`
(slice 5 moved the agent-behavior chain; this slice moves the
rule-config chain). With both gone, `mdc_compiler.clj` drops from 4 to
**3 real layers — within the rule 210 budget.** stratum-lint is clean
on this file with no `MINIFORGE_STRATUM_BUDGET_MODE` override needed
for the first time in this train.

A repo-wide sweep confirms the target and every sibling file this
train created are clean:

```text
$ stratum-lint components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj \
                components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler/
(0 findings)
```

(A component-wide sweep of all of `policy-pack` turns up several
*other* files over budget — `compiler.clj`, `detection.clj`,
`external.clj`, `intent.clj`, `loader.clj`, `mapping.clj`,
`registry.clj`, `repair.clj`, `rules/pack_dependency_validation.clj`,
`taxonomy.clj` — all out of scope for this train, which was
`mdc_compiler.clj`-only.)

## Changes in Detail

- New file `mdc_compiler/rule_config.clj`: the rule-config builders, 2
  real layers (0-1), stratum-lint clean on its own.
- `mdc_compiler.clj`: the four items removed; `mdc->rule` now calls
  `rule-config/build-detection-config`, `rule-config/build-remediation-config`,
  and `rule-config/valid-enforcement-actions`. Ran `stratum-lint --fix`
  to renumber headings/`:stratum` metadata and rewrote the namespace
  docstring's layer summary to reflect the completed train.
- No test file changes: none of the four extracted items had direct
  test coverage in `mdc_compiler_test.clj` (exercised only indirectly
  through `mdc->rule`, whose tests are unaffected).

## Testing Plan

1. `clj-kondo` clean on both touched files.
2. stratum-lint: `rule_config.clj` passes SL003 outright;
   `mdc_compiler.clj` **passes SL003 outright too** — 3 real layers,
   within budget. First clean commit in this train, no
   `MINIFORGE_STRATUM_BUDGET_MODE` override.
3. `ai.miniforge.policy-pack.mdc-compiler-test`: 26 tests / 159
   assertions, 0 failures, 0 errors — unchanged from main.
4. `ai.miniforge.policy-pack.taxonomy-test` (exercises the slice-4
   `export-canonical-taxonomy` delegating var, untouched by this
   slice): 10 tests / 39 assertions, 0 failures, 0 errors.
5. `ai.miniforge.policy-pack.interface-test`: 14 tests / 69 assertions,
   0 failures, 0 errors.
6. Full pre-commit suite (`poly:check`, lint, smoke tests, GraalVM)
   passed on both commits.
7. Adversarial self-review: diffed the full top-level `defn`/`def` set
   before and after — exactly 4 relocated, 0 added/removed/altered in
   behavior; `mdc->rule`'s body is unchanged except for the three
   qualified calls.

PR size: reportable lines checked per-commit (63 for the new file, 97
for the removal commit), both under the 200-line commit budget;
combined well under the 600-line PR budget.

## Deployment Plan

Merges to `main`, completing the 6-PR split train.
`components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj`
and every namespace this train created
(`mdc-compiler.frontmatter-values`, `mdc-compiler.frontmatter`,
`mdc-compiler.condense`, `mdc-compiler.dewey`,
`mdc-compiler.agent-behavior`, `mdc-compiler.rule-config`) are within
the rule 210 3-layer budget.

## Related Issues/PRs

- Slice 1: [#1729](https://github.com/miniforge-ai/miniforge/pull/1729)
- Slice 2: [#1732](https://github.com/miniforge-ai/miniforge/pull/1732)
- Slice 3: [#1733](https://github.com/miniforge-ai/miniforge/pull/1733)
- Slice 4: [#1740](https://github.com/miniforge-ai/miniforge/pull/1740)
- Slice 5: [#1742](https://github.com/miniforge-ai/miniforge/pull/1742)
- Precedent: [dag-orchestrator split, #1485](https://github.com/miniforge-ai/miniforge/pull/1485)
- Precedent: [workflow-runner split, #1662](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2)

## Checklist

- [x] Fan-in re-verified with the corrected namespace-symbol grep
      pattern for all four moved items — none found
- [x] Branch rebased onto latest `origin/main`
- [x] Pure code motion — no behavior change, no logic altered
- [x] `clj-kondo` clean
- [x] Tests green (mdc-compiler 26/26, taxonomy 10/10, interface 14/14)
- [x] Commit-diff budgets checked (63 and 97, both ≤200); PR total
      well under 600
- [x] stratum-lint clean on `mdc_compiler.clj` with NO budget-mode
      override — train complete
