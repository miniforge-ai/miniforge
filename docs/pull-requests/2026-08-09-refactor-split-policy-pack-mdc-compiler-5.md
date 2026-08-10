<!--
  Title: Split policy-pack mdc_compiler.clj — extract agent-behavior (5/6)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split mdc_compiler.clj — extract agent-behavior (5/6)

## Overview

Slice 5 of the 6-PR split train for
`components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj`
(rule 210, SL003 — 9 real layers, max 3; slices 1-4 were
`#1729`/`#1732`/`#1733`/`#1740`). This slice extracts
`ai.miniforge.policy-pack.mdc-compiler.agent-behavior`: the
agent-behavior extraction chain — `behavior-condensation-target`,
`extract-agent-behavior-section`, `extract-first-paragraph`,
`condense-to-length`, `extract-agent-behavior`.

## Motivation

Re-confirmed zero external fan-in on all five moved items (grepped for
`mdc-compiler/<fn-name>` across the repo, the corrected pattern from
slice 4) and rebased onto latest `origin/main` before starting.

**Notable finding**: the file stays at 4 real layers after this
slice, unchanged from slice 4. `mdc->rule` sits atop two independent
chains — this slice's agent-behavior chain, and
`build-remediation-config`'s rule-config chain. Moving only one still
leaves the other setting `mdc->rule`'s depth, so the SL003 count
doesn't move until the final slice removes both. This mirrors the
slice 1→2 pattern (moving a chain that isn't the sole bottleneck still
reduces line count and internal complexity even when the layer number
doesn't drop) — expected, not a regression.

## Changes in Detail

- New file `mdc_compiler/agent_behavior.clj`: the agent-behavior chain,
  2 real layers (0-1), stratum-lint clean on its own.
- `mdc_compiler.clj`: the five items removed; `mdc->rule` now calls
  `agent-behavior/extract-agent-behavior`. Ran `stratum-lint --fix`
  (no rewrite needed — the remaining defs' `:stratum` metadata was
  already correct after the removal) and updated the namespace
  docstring's layer summary — the file stays at 4 real layers; the
  final slice (rule-config builders) is what actually collapses it to
  budget.
- `mdc_compiler_test.clj`: added a require for the new
  `agent-behavior` namespace; `extract-agent-behavior-test`'s 10 call
  sites now call `agent-behavior/extract-agent-behavior` instead of
  `sut/extract-agent-behavior`.

The parent namespace stays over budget (4 real layers) until the
remaining slice lands; the removal commit used
`MINIFORGE_STRATUM_BUDGET_MODE=warn`, same convention as prior slices.

## Testing Plan

1. `clj-kondo` clean on all touched files.
2. stratum-lint: `agent_behavior.clj` passes SL003 outright;
   `mdc_compiler.clj` intentionally still over budget (4 layers,
   unchanged) — expected, see Motivation above.
3. `ai.miniforge.policy-pack.mdc-compiler-test`: 26 tests / 159
   assertions, 0 failures, 0 errors — unchanged from main.
4. Full pre-commit suite (`poly:check`, lint, smoke tests, GraalVM)
   passed on both commits.
5. Adversarial self-review: diffed the full top-level `defn`/`def` set
   before and after — exactly 5 relocated, 0 added/removed/altered in
   behavior; `mdc->rule`'s body is unchanged except for the one
   qualified call.

PR size: reportable lines checked per-commit (88 for the new file, 134
for the removal commit), both under the 200-line commit budget;
combined well under the 600-line PR budget.

## Deployment Plan

Merges to `main` as part of the ongoing 6-PR train. The final slice
rebases onto the updated `main` after this one merges and is expected
to bring `mdc_compiler.clj` (and the whole component) within the
rule-210 budget.

## Related Issues/PRs

- Slice 1: [#1729](https://github.com/miniforge-ai/miniforge/pull/1729)
- Slice 2: [#1732](https://github.com/miniforge-ai/miniforge/pull/1732)
- Slice 3: [#1733](https://github.com/miniforge-ai/miniforge/pull/1733)
- Slice 4: [#1740](https://github.com/miniforge-ai/miniforge/pull/1740)
- Precedent: [dag-orchestrator split, #1485](https://github.com/miniforge-ai/miniforge/pull/1485)
- Precedent: [workflow-runner split, #1662](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2)

## Checklist

- [x] Fan-in re-verified with the corrected namespace-symbol grep
      pattern for all five moved items — none found
- [x] Branch rebased onto latest `origin/main`
- [x] Pure code motion — no behavior change, no logic altered
- [x] `clj-kondo` clean
- [x] Tests green (26/26, 159 assertions)
- [x] Commit-diff budgets checked (88 and 134, both ≤200); PR total
      well under 600
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
