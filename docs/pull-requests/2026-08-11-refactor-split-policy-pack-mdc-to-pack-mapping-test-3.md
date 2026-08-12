<!--
  Title: Split policy-pack mdc_to_pack_mapping_test.clj — extract inventory (3/7)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split mdc_to_pack_mapping_test.clj — extract inventory (3/7)

## Overview

Slice 3 of the 7-PR split of
`components/policy-pack/test/ai/miniforge/policy_pack/mdc_to_pack_mapping_test.clj`
(stratum-lint SL003, rule 210, `standards/miniforge`). See
[slice 1, miniforge#1758](https://github.com/miniforge-ai/miniforge/pull/1758)
for the full train rationale and precedent links.

This slice extracts `ai.miniforge.policy-pack.mdc-to-pack-mapping-test.inventory`:
the complete `.mdc` file inventory from Section 3 of the design spec —
plain data, no dependencies, always a layer-0 island.

## Motivation

Zero fan-in confirmed for the parent test namespace before slice 1
(see miniforge#1758); this slice doesn't change that.

## Changes in Detail

- New file `mdc_to_pack_mapping_test/inventory.clj`: `complete-inventory`,
  moved verbatim.
- New file `mdc_to_pack_mapping_test/inventory_test.clj`: the `deftest`
  forms that exercise it (`inventory-covers-all-mdc-files-test`,
  `inventory-matches-filesystem-test`,
  `pack-structure-categories-cover-all-rules-test`), plus
  `pack-metadata-test` and `all-frontmatter-fields-mapped-test` — the
  latter two have no fixture dependency of their own; they're grouped
  here because both sat under the parent file's "Design spec data"
  section, immediately ahead of the inventory data they now sit beside.
  Assertions unchanged, call sites now go through the `inventory` alias.
- `mdc_to_pack_mapping_test.clj`: `complete-inventory` and the five
  tests above removed; `rule-id-derivation-test`'s remaining "Complete
  inventory matches expected IDs" subtest now calls
  `inventory/complete-inventory` across the namespace boundary. Ran
  `stratum-lint --fix` — no rewrite needed, headings/metadata were
  already consistent; still 4 real layers, unchanged by this slice
  (the inventory data was layer-0 and wasn't on the file's deepest
  chain).

The parent namespace stays over budget (4 real layers) until the
remaining slices land; the commit removing `complete-inventory` used
`MINIFORGE_STRATUM_BUDGET_MODE=warn`, same convention as slices 1-2.

## Testing Plan

1. `clj-kondo` clean on all three touched/new files.
2. stratum-lint: `inventory.clj` and `inventory_test.clj` pass SL003
   outright; `mdc_to_pack_mapping_test.clj` intentionally still over
   budget (4 layers) — expected and tracked, not a defect.
3. Test/assertion count verified unchanged before and after, across
   all four namespaces combined: 39 tests / 1213 assertions, 0
   failures — identical to the slice-2 baseline.
4. Full pre-commit suite (`poly:check`, lint, stratum-lint, smoke
   tests, GraalVM) passed on both commits.
5. Adversarial self-review: diffed the full top-level `def`/`deftest`
   set before and after — exactly 1 def and 5 deftest forms relocated,
   0 added/removed/altered in behavior; the one remaining call site
   (`rule-id-derivation-test`) changed only its qualification
   (`inventory/...`), not its logic.

PR size: 253 reportable lines (240 raw insertions / 135 raw deletions
across 3 files, two commits of 139 and 113 reportable lines each),
under the 600-line budget — no override needed.

## Deployment Plan

Merges to `main` as part of the ongoing 7-PR train. The next slice
rebases onto the updated `main` after this one merges.

## Related Issues/PRs

- Slice 1: [miniforge#1758](https://github.com/miniforge-ai/miniforge/pull/1758)
- Slice 2: [miniforge#1771](https://github.com/miniforge-ai/miniforge/pull/1771)
- Precedent: [mdc_compiler.clj split train, miniforge#1729-#1743](https://github.com/miniforge-ai/miniforge/pull/1743)
- Part of the stratum-lint rule-210 remediation program (Wave 2, policy-pack batch 2)

## Checklist

- [x] Zero fan-in confirmed (unchanged since slice 1)
- [x] Pure code motion — no behavior change, no assertions altered
- [x] `clj-kondo` clean
- [x] Tests green (39/39 tests, 1213/1213 assertions, matches baseline)
- [x] PR-diff and commit-diff budgets checked (253/600 PR, both commits ≤200)
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
