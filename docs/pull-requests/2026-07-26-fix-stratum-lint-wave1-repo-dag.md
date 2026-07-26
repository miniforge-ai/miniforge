<!--
  Title: fix: stratum-lint autofix for components/repo-dag (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/repo-dag (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/repo-dag` (`src` + `test`) to
replace decorative `Layer N` headings with real ones derived from each
file's actual same-file reference graph, and tag every top-level `def`/
`defn`/`deftest` with `^{:stratum n}`. One of the Wave 1 batches from
`work/stratum-lint-baseline-2026-07-24.md`. Mostly mechanical, but two
classes of manual fix were needed beyond the autofix output: one stale
decorative heading that now contradicted its surrounding real headings
in `core.clj`, and six namespace docstrings (two in `src`, four in
`test`) that hardcoded a layer count/range the fix made wrong. No
executable logic changed anywhere.

## Motivation

Baseline findings for this component, confirmed via a fresh plain-lint
run before touching anything (zero `SL001` — no upward-reference/cycle
risk, matching the Wave 1 batch criteria):

- `core.clj`: `SL003`, 4 distinct (decorative) layers against the
  3-layer budget.
- `interface.clj`: `SL003`, 6 distinct (decorative) layers.
- `schema.clj`: `SL003`, 4 distinct (decorative) layers.
- Four `dag_*_test.clj` files (`dag_crud_test`, `dag_queries_test`,
  `dag_topology_test`, `dag_validation_test`): `SL004`, the shared
  `*manager*` dynamic var and `manager-fixture` def sitting before the
  first `Layer` heading — 2 findings each, 8 total.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/repo-dag
```

All 3 `src` files and 11 `test` files were rewritten (`--fix` normalizes
every file it touches, not just the ones with findings). Diffs are
heading text, `^{:stratum n}` metadata, and def/deftest reordering only.

Notable autofix outcomes worth calling out because they look alarming
in a raw diff:

- `interface.clj`'s 6 decorative headings (schema re-exports, manager
  lifecycle, CRUD, queries, validation, pure functions) collapsed to a
  **single** real layer. This file is a thin facade — every def just
  delegates to `core/*` — so there are no same-file references between
  its own defs at all; the 6-layer count the old headings implied was
  entirely decorative.
- Every test file with a `manager-fixture` had its bare
  `(use-fixtures :each manager-fixture)` call swept to the end of the
  file (the tool's documented behavior for unrecognized non-`def`
  top-level forms — see `work/stratum-lint-baseline-2026-07-24.md`).
  Verified this is behaviorally inert here: `use-fixtures` only
  attaches fixture fns to the namespace's metadata, which `clojure.test`
  reads at *test-run* time, long after the whole namespace (including
  this call) has finished loading — so its position relative to the
  `deftest` forms in the source doesn't matter. Confirmed empirically
  too: all 67 tests still pass (see Testing Plan).
- `schema.clj` picked up two whitespace-only re-spacings of already
  same-line trailing comments (`:schema-before-impl])` and
  `:same-pr-train])`, going from inconsistent 1/4-space gaps to a
  uniform 2 spaces before the `;`). Checked both by hand — the comment
  stayed attached to the same form in both cases; no displacement.

One thing the autofix did **not** resolve, found during the mandated
full-diff read: `core.clj` had a stale decorative
`;---- Layer 0.7` sub-banner (with its own "Validation functions"
comment) that the pre-fix file used as one of four undifferentiated
`Layer 0.x` sub-dividers within old `Layer 0`. After the fix moved the
def underneath it (`validate-dag-impl`) to the real `Layer 1`, the
banner ended up sitting *inside* the real `Layer 1` region, reading
"0.7" directly after the genuine `Layer 1` heading above it — backward,
and contradictory. Dropped the `Layer 0.7` heading by hand, keeping
"Validation functions" as a plain comment (same fix class as
`5bc460080`, `verify_test.clj`'s leftover `Layer 1` banner). The other
three `Layer 0.x` sub-dividers in the same file (`0.5`, `0.6`, `0.8`)
were left alone — none of them jump backward past a real heading, so
they aren't contradictory, just superseded decoration.

Also updated by hand, six namespace docstrings whose hardcoded layer
summary the fix invalidated:

- `core.clj` and `schema.clj` each claimed a 3-layer breakdown
  (`Layer 0`/`Layer 1`/`Layer 2`); the fix surfaced 5 real layers in
  each. Rewrote both to describe the actual structure.
- `dag_crud_test.clj`, `dag_queries_test.clj`, `dag_topology_test.clj`,
  and `dag_validation_test.clj` each carried a stale
  `"... (Layers N-M)"` parenthetical referencing an old cross-file test
  numbering scheme (0-2, 3-4, 5-6, 7-9) that predates this tool and
  never corresponded to any single file's real stratum count. Every one
  of these files now has exactly 2 real layers (fixtures, then
  everything that uses them), so the old ranges were doubly wrong.
  Dropped the parenthetical from all four rather than substituting a
  new number that carries no information beyond what the file's own
  `;---- Layer N` headings already show.

No `#?(...)` reader-conditional-wrapped defs in this component, so the
SL008 fix in the current pin never came into play.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before any change — reproduced
   the findings above exactly, confirmed 0 `SL001`.
2. Ran `--fix` over the whole component — 14 files rewritten.
3. Ran `--fix` a second time immediately after — zero diff, confirms
   idempotency.
4. Read the full diff for all 14 changed files. Found and hand-fixed the
   contradictory `Layer 0.7` banner in `core.clj`; updated 6 stale
   namespace docstrings (above); confirmed the two trailing-comment
   re-spacings in `schema.clj` didn't displace anything.
5. Ran `--fix` two more times after the hand edits — zero diff both
   times, confirms the manual fixes are stable under the tool.
6. `clj-kondo --lint components/repo-dag`: 0 errors, 0 warnings — both
   before and after.
7. Ran all 11 test namespaces directly via `clojure -M:dev:test`
   (`add-edge-test`, `add-repo-test`, `queries-test`,
   `remove-edge-test`, `remove-repo-test`, `validate-schema-test` under
   `anomaly.*`, plus `dag-crud-test`, `dag-integration-test`,
   `dag-queries-test`, `dag-topology-test`, `dag-validation-test`): 67
   tests, 230 assertions, 0 failures, 0 errors.
8. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear across the component. `SL003` remains, newly surfaced (higher
   than the pre-fix decorative count, not a regression) on two files:
   - `core.clj`: 5 real layers (pure DAG primitives/protocol/store
     helpers → schema-validating siblings/traversal/validation →
     schema-aware constructors/CRUD impls → the `InMemoryDagManager`
     defrecord → the `create-manager` factory).
   - `schema.clj`: 5 real layers (enums/base types/standalone schemas →
     registry/`infer-layer` → `RepoNode`/`RepoEdge` → `RepoDag`/node-edge
     validators → `valid-repo-dag?`).

   Both are genuinely over the 3-layer budget the old decorative
   headings hid by undercounting (4 vs the real 5), not a regression
   this PR introduces — deferred to Wave 2 (real namespace split),
   consistent with how prior Wave 1 PRs (e.g. `schema`, `control-plane`)
   handled the same situation. `interface.clj`'s original `SL003` is
   fully resolved (collapsed to 1 real layer, well under budget).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order/docstring-only. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward;
`core.clj` and `schema.clj`'s `SL003` stay advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2
splits them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Precedent for the decorative-banner hand-fix: `5bc460080` (fix:
  correct stale/placeholder Layer headings in phase-software-factory
  tests)
- Follow-on: Wave 2 namespace split for
  `components/repo-dag/src/ai/miniforge/repo_dag/core.clj` and
  `components/repo-dag/src/ai/miniforge/repo_dag/schema.clj` (5 real
  layers each, over the 3-layer budget)

## Checklist

- [x] Plain lint confirmed zero `SL001` before touching anything
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 14 changed files
- [x] Contradictory decorative `Layer 0.7` banner (`core.clj`) found and
      removed by hand; keeping "Validation functions" as a plain comment
- [x] Six namespace docstrings (`core.clj`, `schema.clj`,
      `dag_crud_test.clj`, `dag_queries_test.clj`,
      `dag_topology_test.clj`, `dag_validation_test.clj`) updated to
      drop/replace stale layer counts or ranges
- [x] Two further `--fix` passes after hand edits confirm stability
      (zero diff)
- [x] `clj-kondo` clean (0 errors, 0 warnings before/after)
- [x] Component tests pass (67 tests, 230 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero `SL001`/`SL002`/`SL004`; `SL003`
      remains on `core.clj` and `schema.clj` — newly surfaced by the fix
      (not pre-existing), tracked as Wave 2 above
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
