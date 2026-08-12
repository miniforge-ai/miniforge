<!--
  Title: Split policy-pack mdc_to_pack_mapping_test.clj — extract naming (4/4, final)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split mdc_to_pack_mapping_test.clj — extract naming (4/4, final)

## Overview

Final slice of the split of
`components/policy-pack/test/ai/miniforge/policy_pack/mdc_to_pack_mapping_test.clj`
(stratum-lint SL003, rule 210, `standards/miniforge`). See
[slice 1, miniforge#1758](https://github.com/miniforge-ai/miniforge/pull/1758)
for the original train rationale and precedent links.

This slice extracts `ai.miniforge.policy-pack.mdc-to-pack-mapping-test.naming`:
filename/slug/title derivation (`slug-from-filename`, `title-from-slug`,
`rule-id-from-filepath`, `derive-title`) — and, with it, **closes out
the split train at 4 PRs instead of the originally estimated 7**. See
"Why the train ends here" below.

## Motivation

Zero fan-in confirmed for the parent test namespace before slice 1
(see miniforge#1758); this slice doesn't change that.

## Changes in Detail

- New file `mdc_to_pack_mapping_test/naming.clj`: the four functions
  above, moved verbatim. `rule-id-from-filepath` and `derive-title`
  both call `slug-from-filename`; `derive-title` also calls
  `title-from-slug` — a 2-layer same-file chain, so this file was
  already within its own budget before extraction.
- New file `mdc_to_pack_mapping_test/naming_test.clj`: the `deftest`
  forms that exercise them (`slug-from-filename-test`,
  `title-from-slug-test`, `edge-case-duplicate-slugs-test`,
  `rule-id-derivation-test`, `title-derivation-test`) — assertions
  unchanged, call sites now go through the `naming` alias.
  `rule-id-derivation-test`'s "Complete inventory matches expected
  IDs" subtest requires `mdc-to-pack-mapping-test.inventory` directly
  (slice 3), same as it did in the parent file.
- `mdc_to_pack_mapping_test.clj`: the four functions and five tests
  removed; `compile-rule` now calls `naming/derive-title` and
  `naming/rule-id-from-filepath` across the namespace boundary. Also
  dropped the now-unused `inventory` require and `are` refer — their
  only callers in this file were the tests that just moved out. Ran
  `stratum-lint --fix`: **the file is now within budget at 3 real
  layers** (0: `build-applies-to`; 1: `applies-to-test`,
  `compile-rule`; 2: the `compile-rule`-dependent tests) — plain
  `stratum-lint` passes clean, no `MINIFORGE_STRATUM_BUDGET_MODE`
  override needed for this commit (the only slice in this train where
  that was true).

## Why the train ends here

The plan in miniforge#1758 anticipated up to 7 slices, following the
`mdc_compiler.clj` precedent's 6-PR train. That file's SL003 finding
came from **two independent chains** both feeding `mdc->rule`
(frontmatter/dewey and rule-config), needing two extraction passes
plus incidentals. This file's finding turned out to come from **one**
deepest chain: `dewey` → `naming`'s `build-applies-to`/`compile-rule`
→ the `compile-rule`-dependent tests. Moving the dewey chain (slice 1)
and then the naming chain (this slice) onto namespace boundaries
removed both segments feeding `compile-rule`'s depth, so the
remaining ~120-line `build-applies-to`/`compile-rule`/tests block
never needed its own extraction to fit the 3-layer budget.
`fields.clj` and `inventory.clj` (slices 2-3) were layer-0 islands
moved to shrink the file's line count, not because they sat on the
deepest chain — with hindsight they weren't strictly required for
SL003 compliance, but they're accurate, reviewable extractions of
real cohesive concerns and left the file smaller regardless.

## Testing Plan

1. `clj-kondo` clean on all three touched/new files (including the
   `inventory`/`are` unused-require cleanup in the parent file).
2. stratum-lint: `naming.clj` and `naming_test.clj` pass SL003
   outright; `mdc_to_pack_mapping_test.clj` **now passes SL003
   outright too** (3 real layers) — the train's goal, achieved.
3. Test/assertion count verified unchanged before and after, across
   all five namespaces combined: 39 tests / 1213 assertions, 0
   failures — identical to the slice-3 baseline and to the original
   single-file count before slice 1.
4. Full pre-commit suite (`poly:check`, lint, stratum-lint, smoke
   tests, GraalVM) passed on both commits — the second commit passed
   the plain stratum-lint gate with no override, confirming the file
   is genuinely within budget now, not just deferred.
5. Adversarial self-review: diffed the full top-level `defn`/`deftest`
   set before and after — exactly 4 functions and 5 deftest forms
   relocated, 0 added/removed/altered in behavior; the two remaining
   call sites (`compile-rule`) changed only their qualification
   (`naming/...`), not their arguments or logic.

PR size: 197 reportable lines (195 raw insertions / 127 raw deletions
across 3 files, two commits of 110 and 122 reportable lines each),
under the 600-line budget — no override needed.

## Deployment Plan

Merges to `main`. This is the last PR in the
`mdc_to_pack_mapping_test.clj` split train — no further slices planned.

## Related Issues/PRs

- Slice 1: [miniforge#1758](https://github.com/miniforge-ai/miniforge/pull/1758)
- Slice 2: [miniforge#1771](https://github.com/miniforge-ai/miniforge/pull/1771)
- Slice 3: [miniforge#1775](https://github.com/miniforge-ai/miniforge/pull/1775)
- Precedent: [mdc_compiler.clj split train, miniforge#1729-#1743](https://github.com/miniforge-ai/miniforge/pull/1743)
- Part of the stratum-lint rule-210 remediation program (Wave 2, policy-pack batch 2)

## Checklist

- [x] Zero fan-in confirmed (unchanged since slice 1)
- [x] Pure code motion — no behavior change, no assertions altered
- [x] `clj-kondo` clean
- [x] Tests green (39/39 tests, 1213/1213 assertions, matches baseline)
- [x] PR-diff and commit-diff budgets checked (197/600 PR, both commits ≤200)
- [x] `mdc_to_pack_mapping_test.clj` passes plain stratum-lint (no
      warn-mode override needed) — SL003 resolved
