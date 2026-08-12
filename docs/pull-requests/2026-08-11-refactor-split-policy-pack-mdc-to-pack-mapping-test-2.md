<!--
  Title: Split policy-pack mdc_to_pack_mapping_test.clj — extract fields (2/7)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split mdc_to_pack_mapping_test.clj — extract fields (2/7)

## Overview

Slice 2 of the 7-PR split of
`components/policy-pack/test/ai/miniforge/policy_pack/mdc_to_pack_mapping_test.clj`
(stratum-lint SL003, rule 210, `standards/miniforge`). See
[slice 1, miniforge#1758](https://github.com/miniforge-ai/miniforge/pull/1758)
for the full train rationale and precedent links.

This slice extracts `ai.miniforge.policy-pack.mdc-to-pack-mapping-test.fields`:
six independent, dependency-free field-derivation helpers —
`derive-description`, `normalize-globs`, `build-always-inject`,
`build-enforcement`, `extract-agent-behavior-section`,
`extract-first-paragraph`. None of these call each other or anything
else in the parent namespace, so unlike slice 1's dewey chain this was
always a layer-0 island — moving it out shrinks the parent file's bulk
without shortening its deepest same-file call chain.

## Motivation

Zero fan-in confirmed for the parent test namespace before slice 1
(see miniforge#1758); this slice doesn't change that — no other
namespace requires `mdc-to-pack-mapping-test` or its `fields` sibling.

## Changes in Detail

- New file `mdc_to_pack_mapping_test/fields.clj`: the six helpers
  above, all layer 0, moved verbatim (only their `:stratum` tags and
  the file's own header/docstring are new).
- New file `mdc_to_pack_mapping_test/fields_test.clj`: the six
  `deftest` forms that exercise them (`description-generation-test`,
  `always-inject-mapping-test`, `globs-normalization-test`,
  `agent-behavior-section-extraction-test`,
  `first-paragraph-extraction-test`,
  `edge-case-body-only-headings-test`) — assertions unchanged, call
  sites now go through the `fields` alias.
- `mdc_to_pack_mapping_test.clj`: the six functions and six tests
  removed; `build-applies-to` and `compile-rule` now call
  `fields/normalize-globs`, `fields/derive-description`,
  `fields/build-enforcement`, and `fields/build-always-inject` across
  the namespace boundary. Ran `stratum-lint --fix` to renumber the
  remaining defs' `;--- Layer N` headings and `^{:stratum n}`
  metadata — still 4 real layers (unchanged by this slice, as noted
  above; the deepest chain is now `dewey`/`build-applies-to` →
  `derive-title`/`compile-rule` → the `compile-rule`-dependent tests,
  which slices 4 and 6 resolve).

The parent namespace stays over budget (4 real layers) until the
remaining slices land; the commit removing the `fields` helpers used
`MINIFORGE_STRATUM_BUDGET_MODE=warn`, same convention as slice 1.

## Testing Plan

1. `clj-kondo` clean on all three touched/new files.
2. stratum-lint: `fields.clj` and `fields_test.clj` pass SL003
   outright; `mdc_to_pack_mapping_test.clj` intentionally still over
   budget (4 layers) — expected and tracked, not a defect.
3. Test/assertion count verified unchanged before and after, across
   all three namespaces combined: 39 tests / 1213 assertions, 0
   failures — identical to the slice-1 baseline.
4. Full pre-commit suite (`poly:check`, lint, stratum-lint, smoke
   tests, GraalVM) passed on both commits.
5. Adversarial self-review: diffed the full top-level `defn`/`deftest`
   set before and after — exactly 6 functions and 6 deftest forms
   relocated, 0 added/removed/altered in behavior; the four remaining
   call sites (`build-applies-to`, `compile-rule`) changed only their
   qualification (`fields/...`), not their arguments or logic.

PR size: 279 reportable lines (259 raw insertions / 191 raw deletions
across 3 files, two commits of 149 and 171 reportable lines each),
under the 600-line budget — no override needed.

## Deployment Plan

Merges to `main` as part of the ongoing 7-PR train. The next slice
rebases onto the updated `main` after this one merges.

## Related Issues/PRs

- Slice 1: [miniforge#1758](https://github.com/miniforge-ai/miniforge/pull/1758)
- Precedent: [mdc_compiler.clj split train, miniforge#1729-#1743](https://github.com/miniforge-ai/miniforge/pull/1743)
- Part of the stratum-lint rule-210 remediation program (Wave 2, policy-pack batch 2)

## Checklist

- [x] Zero fan-in confirmed (unchanged since slice 1)
- [x] Pure code motion — no behavior change, no assertions altered
- [x] `clj-kondo` clean
- [x] Tests green (39/39 tests, 1213/1213 assertions, matches baseline)
- [x] PR-diff and commit-diff budgets checked (279/600 PR, both commits ≤200)
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
