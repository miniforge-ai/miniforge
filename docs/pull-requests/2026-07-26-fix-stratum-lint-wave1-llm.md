<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: stratum-lint autofix for components/llm (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/llm` (`src` + `test`) to replace
decorative `Layer N` headings with real ones derived from each file's actual
same-file reference graph, and tag every top-level `def`/`defn`/`deftest`
with `^{:stratum n}`. One batch (batch 5) of the Wave 1 program tracked in
`work/stratum-lint-baseline-2026-07-24.md`. Purely mechanical: no hand edits
were needed beyond what `--fix` produced, and no executable logic changed
anywhere.

## Motivation

Baseline findings for this component, confirmed via a fresh plain-lint run
before touching anything (zero `SL001` — no upward-reference/cycle risk,
matching the Wave 1 batch criteria):

- `interface.clj`: `SL003`, 7 distinct (decorative) layers against the
  3-layer budget.
- `protocols/impl/llm_client.clj`: `SL002` (a `Layer 0` heading reappearing
  after `Layer 0`) and `SL003` (4 distinct decorative layers).
- `protocols/records/llm_client.clj`: `SL004`, the `CLIClient` `defrecord`
  appears before the file's first `Layer` heading.
- `test/http_providers_test.clj`: `SL003`, 4 distinct decorative layers.
- `test/interface_test.clj`: `SL002` (three separate `Layer 5` headings
  each reappearing after `Layer 5`) and `SL003` (7 distinct decorative
  layers).

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/llm
```

All 24 `.clj` files in the component were rewritten (`--fix` normalizes
every file, not just the ones with findings): 12 `src` files and 12 `test`
files. Diffs are heading text, `^{:stratum n}` metadata, and def/deftest
reordering only — verified with an automated structural check (read every
top-level form from the pre-fix and post-fix version of each file with the
Clojure reader, strip metadata, compare as a multiset) confirming an
identical set of forms before and after in all 24 files; the only
differences are position and the added `^{:stratum n}` annotations.

Two reorderings worth calling out because they look large in the raw diff:

- `interface.clj` collapsed from 7 decorative layers to 2 real ones
  (`0` and `1`): almost every re-exported function is a direct pass-through
  to another namespace (real stratum 0); only `chat`, `chat-stream`, and
  `create-client-for-model` call another same-file def (`complete`,
  `complete-stream`, `create-client`, `backend-for-model`) and land at
  stratum 1.
- `protocols/impl/llm_client.clj` (2180 lines) is the largest diff by far —
  its real reference graph is 11 layers deep, all correctly monotonic
  (`Layer 0` through `Layer 10`) in the rewritten file. No trailing
  same-line comment was found displaced onto the wrong def anywhere in this
  file or any other file in the component (checked with a targeted grep for
  `)  ; comment` patterns across every changed file).

No decorative `;;----` (double-semicolon) banners were left orphaned next
to a contradicting real heading anywhere in the component. Two ns
docstrings (`model_registry.clj` and `model_selector.clj`) contain a prose
summary of their own layer structure (e.g. `"Layer 0: Model capability
definitions ... Layer 1: Query functions ... Layer 2: Recommendation
logic"`) that is now stale relative to the real layer count `--fix`
computed. Left these untouched, consistent with how the `artifact`
component (an earlier Wave 1 batch) handled the identical situation — these
are docstring prose, not the heading-comment convention stratum-lint
parses, and are out of scope for a mechanical autofix pass.

No `defmethod` in this component, so the upstream `defmethod`-refs-union bug
class (already fixed at this pin) doesn't apply here. No reader-conditional
(`#?(...)`) wrapped `defn` in this component, so `SL008` never came up.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before any change — reproduced
   the findings above exactly, confirmed 0 `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 24 changed files, plus an automated
   structural-equality check (Clojure reader, metadata stripped, multiset
   compare) across all 24 files confirming no code body changed. Checked
   for same-line trailing-comment displacement and orphaned decorative
   banners — none found.
4. `clj-kondo --lint components/llm`: 0 errors, 2 warnings both before and
   after (`cost_test.clj` unresolved `clojure.java.io`/`clojure.edn`
   namespace warnings — pre-existing, confirmed via `git stash` + re-lint;
   unchanged in content and count, only line numbers shifted).
5. Ran all 12 test namespaces directly via `clojure -M:dev:test`: 186
   tests, 1051 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004` clear
   across the component. `SL003` remains on 4 files, all newly surfaced by
   the fix (not present as `SL003` in the pre-fix baseline in this form —
   the pre-fix `SL003`s were on different files with different decorative
   counts):
   - `cost.clj`: 4 real layers (was clean pre-fix; `--fix` surfaced a
     genuine chain through `usage-token-count`/`cost-table`/
     `pricing-for-model`/`estimate-cost`).
   - `model_registry.clj`: 5 real layers (was clean pre-fix; true chain is
     `load-model-catalog` → `model-catalog`/`model-registry` →
     `task-type-recommendations` → query fns → `supports-large-context?`).
   - `model_selector.clj`: 7 real layers (was clean pre-fix; true chain
     runs constants → `default-config-fallback`/`model-selector-config` →
     `default-config`/`provider-env-vars`/`meets-cost-constraint?` →
     `model-available?` → the three `select-by-*` strategies →
     `select-model` → `select-model-for-phase`).
   - `protocols/impl/llm_client.clj`: 11 real layers (was 4 decorative,
     already over budget pre-fix).

   All four are genuinely over the 3-layer budget the old decorative
   headings hid (either by undercounting or by not existing at all) — not
   a regression this PR introduces. Deferred to Wave 2 (real namespace
   split), consistent with how prior Wave 1 PRs handled the same situation.
   `interface.clj`'s, `protocols/records/llm_client.clj`'s,
   `http_providers_test.clj`'s, and `interface_test.clj`'s original findings
   are all fully resolved (collapsed to 2–3 real layers each, under
   budget).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum` autofixer
keeps this component clean going forward; the 4 over-budget files stay
advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2
splits them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1, batch 5)
- Follow-on: Wave 2 namespace split for
  `components/llm/src/ai/miniforge/llm/cost.clj` (4 real layers),
  `components/llm/src/ai/miniforge/llm/model_registry.clj` (5 real
  layers), `components/llm/src/ai/miniforge/llm/model_selector.clj` (7 real
  layers), and
  `components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj` (11
  real layers, 2180 lines — the clearest Wave 2 split candidate in this
  component)

## Checklist

- [x] Confirmed zero `SL001` before making any change
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 24 changed files, plus an automated
      structural-equality check confirming no code body changed
- [x] No same-line trailing-comment displacement found in any file
- [x] No orphaned decorative `;;----` banners found in any file
- [x] `clj-kondo` clean of new issues (0 errors before/after; 2
      pre-existing warnings unchanged in content/count)
- [x] Component tests pass (186 tests, 1051 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero `SL001`/`SL002`/`SL004`; `SL003`
      remains on 4 files — newly surfaced by the fix, tracked as Wave 2
      above
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
