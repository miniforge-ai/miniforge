<!--
  Title: Split policy-pack pack_dependency_validation.clj — extract trust (2/3)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split pack_dependency_validation.clj — extract trust (2/3)

## Overview

Slice 2 of the 3-PR split train for
`components/policy-pack/src/ai/miniforge/policy_pack/rules/pack_dependency_validation.clj`
(rule 210, SL003 — originally 6 real layers, max 3; slice 1 was
[#1770](https://github.com/miniforge-ai/miniforge/pull/1770)). This
slice extracts
`ai.miniforge.policy-pack.rules.pack-dependency-validation.trust`: the
trust-level constraint chain — `tainted-dependency?`,
`untrusted-instruction-escalation?`, `check-dependency-trust`,
`detect-trust-violations`.

## Motivation

Repo-wide fan-in check for the fully-qualified namespace (not a
symbol-prefix guess):

```bash
grep -rlE "ai\.miniforge\.policy-pack\.rules\.pack-dependency-validation\b" \
  --include='*.clj' components bases projects
```

Same 7 external callers as slice 1 (re-confirmed after rebasing onto
the post-#1770 `main`):

- `pack_dependency_graph_test.clj`, `pack_depth_limit_test.clj`,
  `pack_validation_test.clj`, `pack_version_constraint_test.clj`,
  `loader.clj`, `knowledge_safety/detectors.clj` — all call only
  `validate-pack-dependencies` / `validate-single-pack` (unchanged
  entry points). Untouched by this slice.
- `governance_test.clj` — calls `dep-val/detect-trust-violations`
  directly (5 call sites, white-box, bypassing the
  `validate-pack-dependencies` orchestration path). **Updated by this
  slice**: `detect-trust-violations` is no longer resolvable under the
  `pack-dependency-validation` namespace.
- `projects/miniforge/test/` re-checked directly — no match.

## Changes in Detail

- New file
  `rules/pack_dependency_validation/trust.clj`
  (`ai.miniforge.policy-pack.rules.pack-dependency-validation.trust`):
  `tainted-dependency?`, `untrusted-instruction-escalation?` (Layer 0,
  private), `check-dependency-trust` (Layer 1, private, over both
  Layer 0 predicates), `detect-trust-violations` (Layer 2, public —
  the only symbol crossing the namespace boundary). 3 real layers,
  stratum-lint clean. Visibility unchanged for every function — the
  two predicates and `check-dependency-trust` were already `defn-` in
  the parent; `detect-trust-violations` was already public.
- `pack_dependency_validation.clj`: the four functions removed;
  `validate-pack-dependencies` now calls
  `trust/detect-trust-violations` instead of the same-file
  `detect-trust-violations`. Ran `stratum-lint --fix` to renumber the
  remaining defs' heading/`:stratum` metadata (6 → 4 real layers after
  slices 1+2) and rewrote the namespace docstring's layer summary.
- `governance_test.clj`: replaced its `dep-val` alias (which, after
  this slice, would resolve to nothing but the moved-out symbol) with
  a direct require of the new `trust` namespace; all 5
  `dep-val/detect-trust-violations` call sites become
  `trust/detect-trust-violations`. No other symbol from the old
  `dep-val` alias was in use, so the require swap is total (not an
  addition alongside the old one).

Pure code motion — no detection logic changed, no return shapes
changed.

The parent namespace stays over budget (4 real layers) until the
final slice lands; the commit doing the removal used
`MINIFORGE_STRATUM_BUDGET_MODE=warn` to get past the pre-commit gate's
plain-lint check on this known intermediate state, same convention as
slice 1. The final slice (extracting graph-construction) brings it to
3 layers.

## Testing Plan

1. `clj-kondo` clean on all three touched files.
2. stratum-lint: `trust.clj` passes SL003 outright (3 layers);
   `pack_dependency_validation.clj` intentionally still over budget (4
   layers) until slice 3 lands — expected and tracked, not a defect.
3. Directly verified the five affected test namespaces (`bb test`
   change-scope takes ~30 min in this repo and its truncated log
   doesn't reliably show policy-pack-specific results, so this was run
   directly): `cd` to the repo root, `clojure -M:dev:test -e
   "(require 'ai.miniforge.policy-pack.rules.pack-version-constraint-test
   'ai.miniforge.policy-pack.rules.pack-dependency-graph-test
   'ai.miniforge.policy-pack.rules.pack-depth-limit-test
   'ai.miniforge.policy-pack.rules.pack-validation-test
   'ai.miniforge.policy-pack.governance-test)
   (clojure.test/run-tests ...)"` — 23 tests, 78 assertions, 0
   failures, 0 errors.
4. `loader.clj` and `knowledge-safety`/`knowledge-safety.detectors`
   (the two non-test callers) confirmed to `require` cleanly (no
   compile error from the moved symbol).
5. Adversarial self-review: diffed the full top-level `defn`/`def` set
   before and after — exactly 4 relocated, 0 added/removed/altered in
   behavior; `validate-pack-dependencies`'s body is unchanged except
   the one qualified call.

## Deployment Plan

Merges to `main` as part of the ongoing 3-PR train. The final slice
(graph-construction extraction) rebases onto this once merged.

## Related Issues/PRs

- Slice 1: [#1770](https://github.com/miniforge-ai/miniforge/pull/1770)
- Precedent: [mdc-compiler split, #1729-#1743](https://github.com/miniforge-ai/miniforge/pull/1743)
- Precedent: [workflow-runner split, #1662](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2, policy-pack batch 2)

## Checklist

- [x] Fan-in re-confirmed via fully-qualified-namespace grep before
      starting (7 external callers, not zero fan-in)
- [x] Pure code motion — no behavior change, no logic altered
- [x] `clj-kondo` clean
- [x] Tests directly verified (23/23, 78 assertions); loader +
      knowledge-safety callers confirmed loadable
- [x] Commit-diff budget checked (single commit, well under 200)
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
- [x] One external test call site (`governance_test.clj`) updated for
      the moved `detect-trust-violations`; the other 6 caller files
      confirmed unaffected
