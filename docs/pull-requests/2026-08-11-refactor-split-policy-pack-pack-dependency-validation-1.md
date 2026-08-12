<!--
  Title: Split policy-pack pack_dependency_validation.clj — extract versions (1/3)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split pack_dependency_validation.clj — extract versions (1/3)

## Overview

`components/policy-pack/src/ai/miniforge/policy_pack/rules/pack_dependency_validation.clj`
trips stratum-lint SL003: 6 distinct real layers, max 3 (rule 210,
`standards/miniforge`). This is slice 1 of a 3-PR split following the
mdc-compiler (#1729-#1743) and workflow-runner (#1662) precedent: one
cohesive concern per namespace, each new file within the 3-layer
budget, mechanical moves only.

This slice extracts
`ai.miniforge.policy-pack.rules.pack-dependency-validation.versions`:
DateVer version parsing, comparison, and constraint satisfaction —
`parse-version`, `parse-version-constraint`, `compare-versions`,
`satisfies-constraint?`.

## Motivation

Repo-wide fan-in check for the fully-qualified namespace (not a
symbol-prefix guess — an alias-blind grep misses aliased calls, the
exact mistake that broke two earlier PRs in this program):

```bash
grep -rlE "ai\.miniforge\.policy-pack\.rules\.pack-dependency-validation\b" \
  --include='*.clj' components bases projects
```

Found 7 external callers (plus the file itself) — this is NOT a
zero-fan-in split:

- `pack_dependency_graph_test.clj`, `pack_depth_limit_test.clj` — call
  only `sut/validate-pack-dependencies`. Untouched by this slice.
- `pack_validation_test.clj` — calls `sut/validate-pack-dependencies`
  and `sut/validate-single-pack`. Untouched by this slice.
- `governance_test.clj` — calls `dep-val/detect-trust-violations`
  directly. Untouched by this slice (moves in slice 2).
- `pack_version_constraint_test.clj` — calls `sut/parse-version`,
  `sut/compare-versions`, `sut/satisfies-constraint?` directly (via
  `#'sut/...` var-quotes) as well as `sut/validate-pack-dependencies`.
  **Updated by this slice** — the three moved functions are no longer
  resolvable under the `sut` alias.
- `loader.clj`, `knowledge_safety/detectors.clj` — call only
  `dep-validation/validate-pack-dependencies`. Untouched by this
  slice.
- `projects/miniforge/test/` was checked directly (project-level
  integration tests aren't in `bb test`'s change-scope) — no match.

## Changes in Detail

- New file
  `rules/pack_dependency_validation/versions.clj`
  (`ai.miniforge.policy-pack.rules.pack-dependency-validation.versions`):
  `parse-version`, `parse-version-constraint` (Layer 0),
  `compare-versions` (Layer 1, over `parse-version`),
  `satisfies-constraint?` (Layer 2, over `parse-version-constraint` +
  `compare-versions`). 3 real layers, stratum-lint clean. Carries its
  own Rich Comment with the version-parsing/comparison/constraint
  examples moved out of the parent's comment block.
- `pack_dependency_validation.clj`: the four functions removed;
  `detect-version-conflicts` now calls
  `versions/satisfies-constraint?`. Because that's a qualified,
  cross-namespace call it no longer counts toward this file's local
  layer depth, so `detect-version-conflicts` drops from Layer 3 to
  Layer 0 — ran `stratum-lint --fix` to physically relocate it under
  the Layer 0 heading and renumber every other def's heading/`:stratum`
  metadata for the new real-layer count (6 → 5). Namespace docstring's
  layer summary rewritten to match. `detect-version-conflicts`'s
  second parameter is named `versions` (pre-existing, shadows the new
  `versions` namespace alias locally) — harmless: `versions/foo` is a
  qualified symbol resolved via the ns alias table at read time, never
  through local lexical bindings, so the shadowing doesn't affect
  qualified calls. Left as-is rather than renaming, to keep this a
  pure move.
- `pack_version_constraint_test.clj`: added a require for the new
  `versions` namespace; the three white-box `#'sut/parse-version`,
  `#'sut/compare-versions`, `#'sut/satisfies-constraint?` var-quotes
  updated to `#'versions/parse-version` etc. The
  `sut/validate-pack-dependencies` call sites are untouched.

This is pure code motion — no detection logic changed, no return
shapes changed.

The parent namespace stays over budget (5 real layers) until the
remaining two slices land; the commit doing the removal used
`MINIFORGE_STRATUM_BUDGET_MODE=warn` to get past the pre-commit gate's
plain-lint check on the intermediate state, same convention as #1729
and #1662. Slice 3 (extracting the graph-construction group) brings it
to 3 layers.

## Testing Plan

1. `clj-kondo` clean on all three touched files.
2. stratum-lint: `versions.clj` passes SL003 outright (3 layers);
   `pack_dependency_validation.clj` intentionally still over budget (5
   layers) until slices 2-3 land — expected and tracked, not a defect.
3. `bb test` (change-scope): policy-pack component green.
4. Adversarial self-review: diffed the full top-level `defn` set before
   and after — exactly 4 relocated (`parse-version`,
   `parse-version-constraint`, `compare-versions`,
   `satisfies-constraint?`), 0 added/removed/altered in behavior; every
   other def's body is byte-identical, only its `:stratum` tag and
   heading section (for `detect-version-conflicts`, physically
   relocated by `--fix`) moved.

## Deployment Plan

Merges to `main` as part of an ongoing 3-PR train. Each subsequent PR
rebases onto the updated `main` after the prior one merges.

## Related Issues/PRs

- Precedent: [mdc-compiler split, #1729-#1743](https://github.com/miniforge-ai/miniforge/pull/1743)
- Precedent: [workflow-runner split, #1662](https://github.com/miniforge-ai/miniforge/pull/1662)
- Precedent: [knowledge_safety split, PR referenced in this file's git history](https://github.com/miniforge-ai/miniforge/pull/1731)
- Part of the stratum-lint rule-210 remediation program (Wave 2, policy-pack batch 2)

## Checklist

- [x] Fan-in confirmed via fully-qualified-namespace grep before
      starting (7 external callers, not zero fan-in)
- [x] Pure code motion — no behavior change, no logic altered
- [x] `clj-kondo` clean
- [x] Tests green (`bb test` change-scope, policy-pack)
- [x] PR-diff and commit-diff budgets checked (both commits ≤200,
      total well under 600)
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
- [x] One external test call site (`pack_version_constraint_test.clj`)
      updated for the three moved functions; the other 6 caller files
      confirmed unaffected
