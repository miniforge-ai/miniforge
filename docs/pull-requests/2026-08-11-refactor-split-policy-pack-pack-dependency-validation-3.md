<!--
  Title: Split policy-pack pack_dependency_validation.clj — extract graph, final slice (3/3)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split pack_dependency_validation.clj — extract graph, final slice (3/3)

## Overview

Final slice (3/3) of the split train for
`components/policy-pack/src/ai/miniforge/policy_pack/rules/pack_dependency_validation.clj`
(rule 210, SL003 — originally 6 real layers, max 3; slices 1-2 were
[#1770](https://github.com/miniforge-ai/miniforge/pull/1770) and
[#1777](https://github.com/miniforge-ai/miniforge/pull/1777)). This
slice extracts
`ai.miniforge.policy-pack.rules.pack-dependency-validation.graph`:
dependency-graph construction and circular/missing-dependency/depth-limit
detection — `get-pack-dependencies`, `detect-circular-dependencies`,
`detect-missing-dependencies`, `calculate-pack-depths`,
`build-dependency-graph`, `detect-depth-violations`.

## Motivation

Repo-wide fan-in check for the fully-qualified namespace (not a
symbol-prefix guess), re-confirmed after rebasing onto the post-#1777
`main`:

```bash
grep -rlE "ai\.miniforge\.policy-pack\.rules\.pack-dependency-validation\b" \
  --include='*.clj' components bases projects
```

Same 7 external callers as slices 1-2. None of the six functions
moved in this slice had a direct external caller (confirmed by
grepping each function name individually across
`components`/`bases`/`projects`, both fully-qualified and via any
`:as` alias) — every caller reaches them only indirectly, through
`validate-pack-dependencies`/`validate-single-pack`, which are
untouched. **No test file changes needed for this slice.**

- `pack_dependency_graph_test.clj`, `pack_depth_limit_test.clj`,
  `pack_validation_test.clj`, `pack_version_constraint_test.clj`,
  `governance_test.clj`, `loader.clj`,
  `knowledge_safety/detectors.clj` — all call only
  `validate-pack-dependencies`/`validate-single-pack`/`detect-trust-violations`
  (the latter already resolved via the `trust` namespace since slice
  2). Untouched by this slice.
- `projects/miniforge/test/` re-checked directly — no match.

## Changes in Detail

- New file
  `rules/pack_dependency_validation/graph.clj`
  (`ai.miniforge.policy-pack.rules.pack-dependency-validation.graph`):
  `get-pack-dependencies`, `detect-circular-dependencies`,
  `detect-missing-dependencies`, `calculate-pack-depths` (Layer 0),
  `build-dependency-graph` (Layer 1, over `get-pack-dependencies`),
  `detect-depth-violations` (Layer 1, over `calculate-pack-depths`). 2
  real layers, stratum-lint clean. Visibility unchanged for every
  function (all were already public `defn` in the parent).
- `pack_dependency_validation.clj`: the six functions removed. The
  `[ai.miniforge.algorithms.interface :as alg]` require also removed
  — its only user, `detect-circular-dependencies`, moved out.
  `validate-pack-dependencies` now calls
  `dep-graph/build-dependency-graph`,
  `dep-graph/detect-circular-dependencies`,
  `dep-graph/detect-missing-dependencies`, and
  `dep-graph/detect-depth-violations`. The new namespace is aliased
  `dep-graph`, not `graph` — `validate-pack-dependencies` already
  destructures a local `graph` binding from
  `build-dependency-graph`'s return value
  (`{:keys [graph by-id versions]}`), and reusing `graph` as the alias
  would recreate the exact parameter-shadowing pattern Copilot flagged
  on slice 1 (PR #1770, `pack-versions` rename). Ran `stratum-lint
  --fix` — a no-op here since the docstring/heading/`:stratum`
  updates were made by hand ahead of running it and it found nothing
  left to fix. The file now measures 3 real layers (0-2) --
  stratum-lint passes outright, no `MINIFORGE_STRATUM_BUDGET_MODE`
  override needed for the first time in this train. Namespace
  docstring rewritten to reflect the completed train (all three
  slices summarized).

This is pure code motion aside from the `dep-graph` alias-naming
choice above (a mechanical readability precaution, not a logic
change) — no detection logic changed, no return shapes changed.

## Testing Plan

1. `clj-kondo` clean on both touched files.
2. stratum-lint: `graph.clj` passes SL003 outright (2 layers);
   `pack_dependency_validation.clj` **passes SL003 outright too** — 3
   real layers, within budget. First clean commit in this train, no
   `MINIFORGE_STRATUM_BUDGET_MODE` override.
3. A component-wide sweep confirms all four files this train touched
   are clean:

   ```text
   $ stratum-lint pack_dependency_validation.clj \
                   pack_dependency_validation/versions.clj \
                   pack_dependency_validation/trust.clj \
                   pack_dependency_validation/graph.clj
   (0 findings)
   ```

4. Directly verified the five affected test namespaces (`bb test`
   change-scope takes ~30 min in this repo and its truncated log
   doesn't reliably show policy-pack-specific results, so this was run
   directly, same as slices 1-2): `clojure -M:dev:test -e
   "(require 'ai.miniforge.policy-pack.rules.pack-version-constraint-test
   'ai.miniforge.policy-pack.rules.pack-dependency-graph-test
   'ai.miniforge.policy-pack.rules.pack-depth-limit-test
   'ai.miniforge.policy-pack.rules.pack-validation-test
   'ai.miniforge.policy-pack.governance-test)
   (clojure.test/run-tests ...)"` — 23 tests, 78 assertions, 0
   failures, 0 errors (identical to slices 1-2; nothing in this
   slice's scope touches assertions).
5. `loader.clj`, `knowledge-safety`, and `knowledge-safety.detectors`
   confirmed to `require` cleanly.
6. Adversarial self-review: diffed the full top-level `defn`/`def` set
   before and after — exactly 6 relocated, 0 added/removed/altered in
   behavior; `validate-pack-dependencies`'s body is unchanged except
   the four qualified calls.

## Deployment Plan

Merges to `main`, completing the 3-PR split train.
`pack_dependency_validation.clj` and every namespace this train
created (`.versions`, `.trust`, `.graph`) are within the rule 210
3-layer budget.

## Related Issues/PRs

- Slice 1: [#1770](https://github.com/miniforge-ai/miniforge/pull/1770)
- Slice 2: [#1777](https://github.com/miniforge-ai/miniforge/pull/1777)
- Precedent: [mdc-compiler split, #1729-#1743](https://github.com/miniforge-ai/miniforge/pull/1743)
- Precedent: [workflow-runner split, #1662](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2, policy-pack batch 2)

## Checklist

- [x] Fan-in re-confirmed via fully-qualified-namespace grep before
      starting (7 external callers total; zero for the six functions
      moved in this specific slice)
- [x] Pure code motion — no behavior change, no logic altered
- [x] `clj-kondo` clean
- [x] Tests directly verified (23/23, 78 assertions); loader +
      knowledge-safety callers confirmed loadable
- [x] Commit-diff budget checked (well under 200)
- [x] stratum-lint clean on `pack_dependency_validation.clj` with NO
      budget-mode override — train complete
- [x] No test file changes needed — verified none of the six moved
      functions had a direct external caller
