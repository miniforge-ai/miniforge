# fix: stratum-lint autofix for components/pipeline-pack (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/pipeline-pack` (`src` + `test`) to
replace decorative, repeated `Layer N` headings with real ones derived from
the file's actual same-file reference graph, plus `^{:stratum n}` metadata on
every top-level def. Purely mechanical, plus two hand-fixes to ns docstrings
whose "Layer N" summaries went stale once `--fix` revealed the true depth
(see Changes in Detail). One of the smaller per-component Wave 1 PRs from
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`pipeline-pack` carried exactly two findings under the baseline's cargo-cult
diagnosis, both `SL002`: `loader.clj` and `registry.clj` each reused a
`Layer N` heading as a repeated section banner instead of one heading per
real stratum. Zero `SL001` findings, so no upward-reference/cycle risk to
reason about before running the mechanical fixer — matches the baseline's
Wave 1 batch criteria exactly.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/pipeline-pack
```

All 8 files rewritten (5 `src`, 3 `test`) — `--fix` normalizes every file in
the component, not just the two with findings.

- `registry.clj`: the old headings claimed 3 layers (0/1/2) for what is
  actually one — every function here (`create-registry`,
  `register-pack!`, `unregister-pack!`, `get-pack`, `list-packs`,
  `list-pack-ids`, `pack-count`, `validate-pack-trust`) is independent, no
  same-file def calls another. Collapsed to a single real `Layer 0`.
- `loader.clj`: the old headings claimed 3 layers (0/0/1/2); the real
  reference chain is 7 deep: `Layer 0` (EDN/path/registry primitives,
  discovery predicates) → `Layer 1` (`normalize-manifest`, path resolution,
  `discover-packs`) → `Layer 2` (`build-pack-from-manifest`) → `Layer 3`
  (`read-and-validate-manifest`) → `Layer 4` (`load-pack-from-directory`)
  → `Layer 5` (`load-discovered-pack`) → `Layer 6` (`load-all-packs`).
- `interface.clj`, `messages.clj`, `schema.clj`, and the 3 test files only
  gained `^{:stratum n}` tags (and, where headings were already correct,
  no reordering) — no prior findings of their own. `schema.clj`'s real
  depth also came out higher than its old headings claimed: 4 layers
  (0–3) against 3 previously.

No line of executable code changed; diffs are heading text, metadata, and
def/deftest reordering only, with one exception: `schema.clj` and
`loader.clj` each had an `ns` docstring summarizing the (previously
decorative) layer structure by name — e.g. `loader.clj`'s said "Layer 0:
EDN parsing, normalization / Layer 1: Directory loader.../ Layer 2:
Discovery and batch loading". `--fix` only rewrites headings and metadata,
never prose, so both docstrings would otherwise have been left stating a
3-layer structure that no longer matches the real 4- and 7-layer ones
`--fix` just computed — a wrong architectural claim is worse than none
(same principle rule 210 applies to headings). Hand-rewrote both docstrings
to list the real layers by content, matching what a later batch
(`adapter-claude-code`) did after automated review flagged the identical
staleness there. Re-ran `--fix` after the docstring edits: zero diff,
confirming it doesn't touch prose either way.

Neither file shows the other known tool-limitation pattern (a same-line
trailing comment displaced onto a different def) — grepped for `;;----`
double-semicolon decorative banners across the whole component: none
found, so that hand-fix path didn't apply here.

`loader.clj` and `schema.clj` now report `SL003` — 7 and 4 real layers
respectively, against the 3-layer budget. Both are **newly surfaced by this
fix**, not pre-existing: confirmed via `git show origin/main:...` that the
old headings on both files topped out at `Layer 2` (three or fewer labels),
so no `SL003` was reported at baseline — the decorative headings were
under-counting the real depth, not just mislabeling it. The true depth was
invisible until `--fix`'s reference-graph analysis exposed it. Namespace
split is Wave 2 work, consistent with how prior Wave 1 PRs (`bb-config`,
`decision`, `compliance-scanner`) handled the same situation.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's exact 2 `SL002` findings (`loader.clj`, `registry.clj`).
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency. Hand-edited the two stale docstrings, then ran
   `--fix` a third time — zero diff again, confirming the tool never
   touches prose.
3. Read the full diff for all 8 changed files (not just `--stat`).
   Confirmed changes are heading text, `^{:stratum n}` metadata, and
   def/deftest reordering, plus the two docstring hand-edits described
   above. Grepped the whole component for `;;----` decorative banners:
   none survived (there were none to begin with), so the second known
   tool-limitation pattern doesn't apply here either.
4. `clj-kondo --lint components/pipeline-pack`: 0 errors, 0 warnings.
5. Ran all 3 test namespaces directly via `clojure -A:test`:
   `ai.miniforge.pipeline-pack.interface-test`,
   `ai.miniforge.pipeline-pack.loader-test`,
   `ai.miniforge.pipeline-pack.registry-test` — 10 tests, 45 assertions,
   0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear. `SL003` remains on `loader.clj` (7 layers) and `schema.clj`
   (4 layers) — expected, newly surfaced (see above), tracked as Wave 2,
   not a defect in this PR.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order/docstring-only. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward;
`loader.clj`'s and `schema.clj`'s `SL003` stay advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2 splits
them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for
  `components/pipeline-pack/src/ai/miniforge/pipeline_pack/loader.clj`
  (7 real layers) and
  `components/pipeline-pack/src/ai/miniforge/pipeline_pack/schema.clj`
  (4 real layers), both over the 3-layer budget.

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff); third pass
      after hand-editing two stale docstrings also zero diff
- [x] Diff read in full for all 8 changed files; mechanical plus two
      documented, verified docstring hand-fixes
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component tests pass (10 tests, 45 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except `SL003`
      (`loader.clj`, `schema.clj`, documented above, newly surfaced,
      tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
