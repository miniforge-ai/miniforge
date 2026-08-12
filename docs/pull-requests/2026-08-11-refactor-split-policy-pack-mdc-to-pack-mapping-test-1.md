<!--
  Title: Split policy-pack mdc_to_pack_mapping_test.clj — extract dewey (1/7)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split mdc_to_pack_mapping_test.clj — extract dewey (1/7)

## Overview

`components/policy-pack/test/ai/miniforge/policy_pack/mdc_to_pack_mapping_test.clj`
trips stratum-lint SL003: 6 distinct layers, max 3 (rule 210,
`standards/miniforge`). This is a test file (989 lines, the largest in
the current Wave 2 batch) with an embedded reference implementation of
the MDC-to-Pack field-mapping spec — it requires nothing from
production `mdc_compiler.clj`, so the same layering discipline that
split that source file (miniforge#1729-#1743) applies here to its own
helper chain and `deftest` groups.

This is slice 1 of a planned 7-PR split, one cohesive concern per
namespace, each new file within the 3-layer budget, mechanical moves
only — same convention as the `mdc_compiler.clj` and `knowledge_safety.clj`
trains.

This slice extracts `ai.miniforge.policy-pack.mdc-to-pack-mapping-test.dewey`:
the dewey-code → phase-set chain (`parse-dewey`, `dewey-range-to-phases`,
`default-phases`, `all-phases`, `dewey-ranges`, `dewey-to-phases`) — the
deepest same-file dependency chain in the parent namespace, feeding
`build-applies-to` and then `compile-rule`.

## Motivation

Confirmed zero fan-in repo-wide before starting
(`grep -rlE "ai\.miniforge\.policy-pack\.mdc-to-pack-mapping-test\b"`
across `components`/`bases`/`projects` matches only the file's own `ns`
form and its `(comment ...)` block) — no other namespace requires this
test namespace, so the split is a pure internal reorganization.

## Changes in Detail

- New file `mdc_to_pack_mapping_test/dewey.clj`: `parse-dewey`,
  `dewey-range-to-phases`, `default-phases`, `all-phases` (layer 0),
  `dewey-ranges` (layer 1), `dewey-to-phases` (layer 2) — moved
  verbatim, 3 real layers, stratum-lint clean.
- New file `mdc_to_pack_mapping_test/dewey_test.clj`: the six
  `deftest` forms that exercise this chain (`dewey-to-phases-test`,
  `dewey-default-phases-test`, `dewey-missing-defaults-to-000-test`,
  `dewey-ranges-non-overlapping-test`, `dewey-ranges-cover-0-to-999-test`,
  `phase-injection-role-equivalence-test`) — assertions unchanged,
  only the call sites now go through the `dewey` alias.
- `mdc_to_pack_mapping_test.clj`: the six functions and six tests
  removed; `build-applies-to` and the three remaining call sites that
  used the bare `all-phases` symbol (`worked-example-a-stratified-design-test`,
  `edge-case-missing-dewey-test`, `edge-case-index-mdc-test`) now call
  `dewey/dewey-to-phases` / `dewey/all-phases` across the namespace
  boundary. Ran `stratum-lint --fix` to renumber the remaining defs'
  `;--- Layer N` headings and `^{:stratum n}` metadata (6 → 4 real
  layers).

The parent namespace stays over budget (4 real layers) until the
remaining slices land; the commit removing the dewey chain used
`MINIFORGE_STRATUM_BUDGET_MODE=warn` to get past the pre-commit gate's
plain-lint check on this intermediate state, same convention as
miniforge#1729. The final slice in this train brings it to 3 layers
(or removes the file entirely, once every `deftest` has moved to a
sibling namespace — this file has no production entry point to keep,
unlike `mdc_compiler.clj`).

## Testing Plan

1. `clj-kondo` clean on all three touched/new files.
2. stratum-lint: `dewey.clj` and `dewey_test.clj` pass SL003 outright;
   `mdc_to_pack_mapping_test.clj` intentionally still over budget (4
   layers) until the rest of the train lands — expected and tracked,
   not a defect.
3. Test/assertion count verified unchanged before and after, across
   both namespaces combined: 39 tests / 1213 assertions, 0 failures —
   identical to the pre-split baseline (single namespace, 39/1213).
4. Full pre-commit suite (`poly:check`, lint, stratum-lint, smoke
   tests, GraalVM) passed on both commits.
5. Adversarial self-review: diffed the full top-level `defn`/`def`/
   `deftest` set before and after — exactly 6 functions/defs and 6
   deftest forms relocated, 0 added/removed/altered in behavior; every
   other def's body is byte-identical, only its `:stratum` tag,
   heading section, or (for the three cross-file call sites) the
   `dewey/` qualification changed.

PR size: 314 reportable lines (350 raw insertions / 180 raw deletions
across 3 files, two commits of 133 and 164 reportable lines each),
under the 600-line budget — no override needed.

## Deployment Plan

Merges to `main` as part of an ongoing 7-PR train. Each subsequent PR
rebases onto the updated `main` after the prior one merges.

## Related Issues/PRs

- Precedent: [mdc_compiler.clj split train, miniforge#1729-#1743](https://github.com/miniforge-ai/miniforge/pull/1743)
- Precedent: [knowledge_safety.clj split, miniforge#1731](https://github.com/miniforge-ai/miniforge/pull/1731)
- Precedent: [workflow-runner split, miniforge#1662](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2, policy-pack batch 2)

## Checklist

- [x] Zero fan-in confirmed via repo-wide grep before starting
- [x] Pure code motion — no behavior change, no assertions altered
- [x] `clj-kondo` clean
- [x] Tests green (39/39 tests, 1213/1213 assertions, matches baseline)
- [x] PR-diff and commit-diff budgets checked (314/600 PR, both commits ≤200)
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
