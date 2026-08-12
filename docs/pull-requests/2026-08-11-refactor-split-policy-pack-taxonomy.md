<!--
  Title: Split policy-pack/taxonomy.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split taxonomy.clj (rule 210)

## Overview

Splits the taxonomy artifact's category schemas and lookup helpers out
of `ai.miniforge.policy-pack.taxonomy` into a new sibling namespace,
`ai.miniforge.policy-pack.taxonomy.category`, resolving a stratum-lint
SL003 finding (the combined namespace measured 4 real layers, over
the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program's policy-pack
Wave 2, batch 2. `taxonomy.clj` (199 lines) is one of this batch.
Unlike most files remaining in this program, this one is not
zero-fan-in: two files inside the same component reference it
directly (`interface/taxonomy.clj`, `taxonomy_test.clj`), so this
split required updating call sites, not just moving code.

## Changes in Detail

- New file `taxonomy/category.clj`: `TaxonomyCategory`, `TaxonomyAlias`,
  `TaxonomyRef` schemas, the composed `Taxonomy` schema, and the pure
  lookup functions `category-by-id`, `resolve-alias`, `category-title`,
  `category-order` — 2 layers. None of this needs malli or EDN/IO, so
  the new namespace has no `:require` clause at all.
- `taxonomy.clj`: keeps `valid-taxonomy?`/`validate-taxonomy` (malli
  validation over `category/Taxonomy`) and `load-taxonomy`/
  `load-taxonomy-from-classpath` (EDN/classpath loading) — now 2
  layers (down from 4). Requires `taxonomy.category` for the schema.
  The rich-comment block at the bottom is unchanged, since both
  functions it exercises (`valid-taxonomy?`, `load-taxonomy-from-classpath`)
  stayed in this namespace.
- `interface/taxonomy.clj`: this Polylith interface file re-exports
  every taxonomy def via a `def` alias. Updated the 8 defs that moved
  (`Taxonomy`, `TaxonomyCategory`, `TaxonomyAlias`, `TaxonomyRef`,
  `category-by-id`, `resolve-alias`, `category-title`,
  `category-order`) to delegate to `category/*` instead of
  `taxonomy/*`; the 4 that stayed (`valid-taxonomy?`,
  `validate-taxonomy`, `load-taxonomy`, `load-taxonomy-from-classpath`)
  are untouched. The interface's own public API surface — the 12 defs
  it exposes to the rest of the workspace — is unchanged; nothing
  outside `policy-pack` needed to change.
- `taxonomy_test.clj`: added a `category` alias for
  `ai.miniforge.policy-pack.taxonomy.category` alongside the existing
  `sut` alias. Updated call sites for the 8 moved symbols
  (`TaxonomyRef`, `category-by-id`, `resolve-alias`, `category-title`,
  `category-order`, and their schema/lookup test groups) to
  `category/*`; left the `sut/valid-taxonomy?`, `sut/validate-taxonomy`,
  `sut/load-taxonomy-from-classpath` call sites alone since those defs
  didn't move.

This is pure code motion — no logic changed. The def set is identical
before and after (12 defs total, same names), and the interface file's
exposed public API is byte-identical (diffed both def-name lists to
confirm).

## Testing Plan

- Fan-in check before starting: `grep -rlE
  "ai\.miniforge\.policy-pack\.taxonomy\b" --include='*.clj' components
  bases projects` — grepping the namespace symbol (not the underscored
  file path, the mistake that bit `mdc_compiler.clj`'s split,
  miniforge#1740) found exactly 3 files: the target itself,
  `interface/taxonomy.clj`, and `taxonomy_test.clj`. Also checked
  `mdc_compiler/dewey.clj` (which builds an
  `export-canonical-taxonomy` map structurally matching the `Taxonomy`
  schema) — it does not `:require` `ai.miniforge.policy-pack.taxonomy`
  at all, so it's not a caller here.
- `projects/miniforge/test/` grepped separately for any taxonomy
  reference — none found. No project-level caller exists for this
  file (unlike the `knowledge_safety.clj` split, which had one in
  `governance/e2e_test.clj`).
- `stratum-lint` clean (exit 0) on all four touched/new files, before
  and after `--fix` (no changes from `--fix` — files were already
  correctly formatted).
- Direct component-test verification (bypassing the shared machine's
  contended `bb test` — several other stratum-lint batch tasks were
  running concurrently, which pushed the full change-scope run past
  30 minutes): `cd components/policy-pack && clojure -M:test -e
  "(require 'ai.miniforge.policy-pack.taxonomy-test)
  (require 'ai.miniforge.policy-pack.mdc-compiler-test)
  (require 'ai.miniforge.policy-pack.interface-test)
  (clojure.test/run-tests 'ai.miniforge.policy-pack.taxonomy-test
  'ai.miniforge.policy-pack.mdc-compiler-test
  'ai.miniforge.policy-pack.interface-test)"` — 50 tests, 267
  assertions, 0 failures. `mdc-compiler-test` and `interface-test`
  were included because they exercise `export-canonical-taxonomy` and
  the full `interface/taxonomy.clj` re-export surface respectively.
- `bb commit-budget` / PR-total line count kept under the 200/600
  ceilings by splitting into two commits: the code split itself
  (165 reportable lines) and the caller updates (48 reportable lines).

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 remediation program, policy-pack
  Wave 2 batch 2 (see `knowledge_safety.clj`/`knowledge_safety/detectors.clj`
  miniforge#1731 and the `mdc_compiler.clj`/`mdc_compiler/dewey.clj`
  split miniforge#1740 for the established convention and the
  fan-in-grep methodology this follows).

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] Component tests green (`taxonomy-test`, `mdc-compiler-test`,
      `interface-test` — 267 assertions, 0 failures), verified directly
- [x] Adversarial self-review: def set unchanged (relocated only), no
      logic changes; interface's public API surface byte-identical
- [x] All call sites updated: `interface/taxonomy.clj`,
      `taxonomy_test.clj` — no project-level caller exists
- [x] Zero-fan-in check done via fully-qualified namespace grep, not a
      symbol-prefix guess
