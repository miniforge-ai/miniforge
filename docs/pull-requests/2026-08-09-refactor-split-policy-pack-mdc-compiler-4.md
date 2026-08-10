<!--
  Title: Split policy-pack mdc_compiler.clj — extract dewey (4/6)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split mdc_compiler.clj — extract dewey (4/6)

## Overview

Slice 4 of the 6-PR split train for
`components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj`
(rule 210, SL003 — 9 real layers, max 3; slices 1-3 were
`#1729`/`#1732`/`#1733`). This slice extracts
`ai.miniforge.policy-pack.mdc-compiler.dewey`: the Dewey-range table
and its lookups — `dewey-ranges`, `default-phases`, `find-dewey-range`,
`export-canonical-taxonomy`, `dewey->phases`, `dewey->category-id`,
`dewey->category-label`.

## Motivation

**Important correction to this train's fan-in check.** Slices 1-3
stated "zero fan-in" based on
`grep -rl "policy[-_]pack\.mdc_compiler\b"` — a pattern built for the
underscored file-path spelling (`mdc_compiler`), not the hyphenated
namespace symbol (`mdc-compiler`) Clojure actually uses in `:require`
forms. Re-checking with the correct pattern
(`grep -rl "ai\.miniforge\.policy-pack\.mdc-compiler\b"`) found real
fan-in: `ai.miniforge.policy-pack.interface` requires `mdc-compiler`
and re-exports `compile-standards-pack` and `export-canonical-taxonomy`
by name, and this component's `taxonomy_test.clj` calls
`mdc-compiler/export-canonical-taxonomy` directly. `compile-standards-pack`
was never planned to move (it's the file's own top-level orchestrator),
so this didn't affect slices 1-3. `export-canonical-taxonomy` **is**
part of this slice's Dewey-range group, so it needed handling here.

Fix: `export-canonical-taxonomy` is re-exported from `mdc_compiler.clj`
as a thin delegating var —
`(def export-canonical-taxonomy dewey/export-canonical-taxonomy)` —
rather than only existing in the new `dewey` namespace. This keeps
`mdc-compiler`'s public surface unchanged, matching the "no public-API
changes" guarantee from the compliance-scanner precedent
(miniforge#1580). Verified `interface.clj` still resolves and returns
the correct taxonomy through the delegate, and `taxonomy_test.clj`
passes unchanged.

No other function in this slice, or in slices 5-6's planned groups,
has external fan-in — re-verified the corrected grep pattern against
every remaining function name before proceeding.

## Changes in Detail

- New file `mdc_compiler/dewey.clj`: the Dewey-range table and lookups,
  3 real layers (0-2), stratum-lint clean on its own.
- `mdc_compiler.clj`: the six standalone functions/defs removed;
  `export-canonical-taxonomy` becomes a delegating var (see Motivation);
  `build-categories` and `mdc->rule` now call `dewey/dewey->category-id`,
  `dewey/dewey->category-label`, and `dewey/dewey->phases`. Ran
  `stratum-lint --fix` to renumber headings/`:stratum` metadata and
  updated the namespace docstring's layer summary — dropped from 5 to
  4 real layers. The surviving critical path runs through two
  independent branches feeding `mdc->rule`
  (`build-remediation-config`'s rule-config chain, and
  `extract-agent-behavior`'s chain) — both need to move before the
  file drops to budget, which slices 5-6 target.
- `mdc_compiler_test.clj`: `sut/dewey->phases`,
  `sut/dewey->category-id`, and `sut/dewey->category-label` call sites
  now go through the new `dewey` namespace directly.

The parent namespace stays over budget (4 real layers) until the
remaining slices land; the removal commit used
`MINIFORGE_STRATUM_BUDGET_MODE=warn`, same convention as prior slices.

## Testing Plan

1. `clj-kondo` clean on all touched files.
2. stratum-lint: `dewey.clj` passes SL003 outright; `mdc_compiler.clj`
   intentionally still over budget (4 layers, down from 5) — expected,
   see Motivation above.
3. `ai.miniforge.policy-pack.mdc-compiler-test`: 26 tests / 159
   assertions, 0 failures, 0 errors.
4. `ai.miniforge.policy-pack.taxonomy-test` (exercises
   `export-canonical-taxonomy` through the `mdc-compiler` alias
   directly, exactly the fan-in path this PR had to preserve): 10
   tests / 39 assertions, 0 failures, 0 errors.
5. `ai.miniforge.policy-pack.interface-test`: 14 tests / 69 assertions,
   0 failures, 0 errors.
6. Full pre-commit suite (`poly:check`, lint, smoke tests, GraalVM)
   passed on both commits.
7. Adversarial self-review: diffed the full top-level `defn`/`def` set
   before and after — six defs relocated, one (`export-canonical-taxonomy`)
   turned into a delegating var with identical return value (verified
   via `interface.clj`'s live call, byte-identical output), 0 other
   behavior changes.

PR size: reportable lines checked per-commit (100 for the new file,
188 for the removal commit, 51 for the test-update commit), all under
the 200-line commit budget; combined well under the 600-line PR budget.

## Deployment Plan

Merges to `main` as part of the ongoing 6-PR train. The next slice
rebases onto the updated `main` after this one merges.

## Related Issues/PRs

- Slice 1: [#1729](https://github.com/miniforge-ai/miniforge/pull/1729)
- Slice 2: [#1732](https://github.com/miniforge-ai/miniforge/pull/1732)
- Slice 3: [#1733](https://github.com/miniforge-ai/miniforge/pull/1733)
- Precedent (no-public-API-changes guarantee): [compliance-scanner split, #1580](https://github.com/miniforge-ai/miniforge/pull/1580)
- Precedent: [dag-orchestrator split, #1485](https://github.com/miniforge-ai/miniforge/pull/1485)
- Precedent: [workflow-runner split, #1662](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2)

## Checklist

- [x] Fan-in re-verified with the correct namespace-symbol grep
      pattern (not just file-path spelling) — found and handled real
      external fan-in on `export-canonical-taxonomy`
- [x] Branch rebased onto latest `origin/main`
- [x] Pure code motion for six of seven moved items; one
      (`export-canonical-taxonomy`) deliberately kept as a delegating
      var to preserve the public API — documented and verified
- [x] `clj-kondo` clean
- [x] Tests green (mdc-compiler 26/26, taxonomy 10/10, interface 14/14)
- [x] Commit-diff budgets checked (100/188/51, all ≤200); PR total
      well under 600
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
