<!--
  Title: fix: stratum-lint autofix for components/repo-index (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/repo-index (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/repo-index` (`src` + `test`) to
replace decorative `Layer N` headings with real ones derived from each
file's actual same-file reference graph, and tag every top-level `def`/
`defn`/`deftest` with `^{:stratum n}`. One of the Wave 1 batches from
`work/stratum-lint-baseline-2026-07-24.md` — the largest so far in this
batch (36 pre-existing findings). One manual fix beyond the autofix
output: `interface.clj`'s namespace docstring hardcoded a 3-layer
breakdown that the fix invalidated. No executable logic changed anywhere.

## Motivation

Baseline findings for this component, confirmed via a fresh plain-lint
run before touching anything (zero `SL001` — no upward-reference/cycle
risk, matching the Wave 1 batch criteria): 35 `SL002` (decorative
`Layer N` headings repeated as section banners instead of one heading
per real stratum) across `factory.clj`, `interface.clj`, `repo_map.clj`,
`scanner.clj`, `schema.clj`, `search_lex.clj`, and all 4 test files that
already carried a heading; plus 1 `SL003` (`interface.clj`, reporting 4
distinct layers against the 3-layer budget). 36 findings total, matching
the baseline document exactly.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/repo-index
```

13 files were rewritten: 8 in `src` (`factory.clj`, `interface.clj`,
`messages.clj`, `repo_map.clj`, `scanner.clj`, `schema.clj`,
`search_lex.clj`, `storage.clj`) and 5 in `test`
(`factory_test.clj`, `interface_test.clj`, `repo_map_test.clj`,
`schema_test.clj`, `search_lex_test.clj`). `--fix` normalizes every file
it touches, not just the ones with pre-existing findings —
`messages.clj`, `storage.clj`, and `interface_test.clj` had zero
findings under plain lint (no `Layer` heading at all, so the plain
checker silently skips them) but still picked up a first real `Layer 0`
heading and `^{:stratum n}` metadata from `--fix`. Diffs are heading
text, `^{:stratum n}` metadata, and def/deftest reordering only.

Notable autofix outcomes worth calling out because they look alarming in
a raw diff:

- `schema.clj` reordered `RepoMapEntry` and `Snippet` ahead of
  `RepoIndex`/`RepoMapSlice`/`SearchHit`: the latter three reference the
  former as nested Malli schemas (`[:vector RepoMapEntry]`,
  `[:vector Snippet]`), a real same-file dependency the old headings
  didn't reflect (everything was decoratively labeled `Layer 0`).
  Verified both new layers (0 and 1) are internally dependency-free and
  correctly ordered.
- `interface.clj` collapsed from 4 decorative layers to 3 real ones (0,
  1, 2) and now clears `SL003` — the file's facade functions
  (`build-index`, `repo-map`, `build-search-index`, `search-lex`,
  `find-in-index`, `read-file-limited`, `find-files`) turned out to have
  no same-file dependencies on each other, all landing at Layer 0;
  `repo-map-text`/`get-file`/`files-by-language` depend on those and
  landed at Layer 1; `get-files` depends on `get-file` and landed at
  Layer 2.
- `repo_map.clj`, `scanner.clj`, `search_lex.clj`, and `storage.clj` each
  surfaced a *higher* real layer count than any decorative heading in the
  file previously implied (7, 7, 8, and 5 real layers respectively,
  against a 3-layer budget) — see the `SL003` note under Testing Plan.
  Traced each file's full same-file call chain by hand to confirm the
  fix's reordering is a correct topological sort with no forward
  references (e.g. `repo_map.clj`: `chars-per-token` → `estimate-tokens`
  → `render-group-rows` → `fit-partial-group` → `render-directory-group`
  → `walk-directory-groups` → `generate`, each layer's defs calling only
  defs at a strictly lower layer).
- `schema_test.clj` and `repo_map_test.clj` reordered several `deftest`
  forms below the test-fixture helpers they call (`valid-repo-index`,
  `big-index`) once those helpers themselves picked up a non-zero
  stratum from calling another same-file helper. Reordering `deftest`
  forms has no behavioral effect; confirmed via the full test run below.

One thing the autofix did **not** resolve, found during the mandated
full-diff read: `interface.clj`'s namespace docstring carried a
hardcoded `Layer 0: Schema re-exports` / `Layer 1: Index building and
repo map` / `Layer 2: File retrieval` breakdown that no longer matched
the real structure above (index/map/search building and index lookups
are now all Layer 0; `repo-map-text`/`get-file`/`files-by-language` are
Layer 1; only `get-files` is Layer 2). This is plain docstring prose, not
a `;---- Layer N` banner the tool parses, so `--fix` left it untouched.
Rewrote it by hand to describe the actual post-fix structure.

No `#?(...)` reader-conditional-wrapped defs in this component, so the
SL008 fix in the current pin never came into play.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before any change — reproduced
   the baseline's 36 findings exactly (35 `SL002`, 1 `SL003`), confirmed
   0 `SL001`.
2. Ran `--fix` over the whole component — 13 files rewritten.
3. Ran `--fix` a second time immediately after — zero diff, confirms
   idempotency.
4. Read the full diff for all 13 changed files. Traced the real
   same-file reference graph by hand for each file that reordered defs
   (`schema.clj`, `interface.clj`, `repo_map.clj`, `scanner.clj`,
   `search_lex.clj`, `schema_test.clj`, `repo_map_test.clj`) to confirm
   every reorder is a correct topological sort — no forward references,
   no cycles. Found and hand-fixed the one stale namespace docstring in
   `interface.clj` (above). Checked all same-line trailing comments
   (none present in this component's changed regions) and all remaining
   `;; Layer N` style comments in the diff (none contradicted a
   regenerated heading).
5. Ran `--fix` once more after the hand edit — zero diff, confirms the
   manual fix is stable under the tool.
6. `clj-kondo --lint components/repo-index`: 0 errors, 0 warnings — both
   before (via `git stash`) and after.
7. Ran all 5 test namespaces directly via `clojure -M:dev:test`
   (`factory-test`, `interface-test`, `repo-map-test`, `schema-test`,
   `search-lex-test`): 64 tests, 3668 assertions, 0 failures, 0 errors.
8. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear across the component. `SL003` remains, newly surfaced (higher
   than the pre-fix decorative count, not a regression) on four files:
   - `repo_map.clj`: 7 real layers (token estimation/formatting
     primitives → budget-aware row rendering → partial-group fitting →
     directory-group rendering → the directory walk → `generate`).
   - `scanner.clj`: 7 real layers (config path/git-command/parsing
     primitives → config loading and git tree/rev-parse wrappers →
     the memoized config delay → config accessors → language/generated
     detection → entry enrichment → `scan`).
   - `search_lex.clj`: 8 real layers (tokenization/index/BM25/snippet/
     candidate primitives → config loading and doc-entry/snippet
     building → the memoized config delay and `build-search-index` →
     BM25 config accessors → `bm25-term-score` → `score-document` →
     `score-and-rank` → `search`).
   - `storage.clj`: 5 real layers (the index-dir-name constant →
     `index-dir` → `index-file` → `save-index`/`load-index` →
     `cached-index`).

   All four are genuinely over the 3-layer budget the old decorative
   headings hid (each file's pre-fix headings topped out at 1 or 2,
   never reflecting the real depth), not a regression this PR
   introduces — deferred to Wave 2 (real namespace split), consistent
   with how prior Wave 1 PRs handled the same situation.
   `interface.clj`'s original `SL003` is fully resolved (down to 3 real
   layers, at budget).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order/docstring-only. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward;
`repo_map.clj`, `scanner.clj`, `search_lex.clj`, and `storage.clj`'s
`SL003` stay advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit
time) until Wave 2 splits them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for
  `components/repo-index/src/ai/miniforge/repo_index/repo_map.clj`,
  `scanner.clj`, `search_lex.clj`, and `storage.clj` (5-8 real layers
  each, over the 3-layer budget)

## Checklist

- [x] Plain lint confirmed zero `SL001` before touching anything
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 13 changed files; reorderings traced
      against each file's real same-file reference graph by hand
- [x] Stale namespace docstring layer breakdown in `interface.clj`
      updated to describe the actual post-fix structure
- [x] Further `--fix` pass after the hand edit confirms stability (zero
      diff)
- [x] `clj-kondo` clean (0 errors, 0 warnings before/after)
- [x] Component tests pass (64 tests, 3668 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero `SL001`/`SL002`/`SL004`; `SL003`
      remains on `repo_map.clj`, `scanner.clj`, `search_lex.clj`, and
      `storage.clj` — newly surfaced by the fix (not pre-existing),
      tracked as Wave 2 above
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
