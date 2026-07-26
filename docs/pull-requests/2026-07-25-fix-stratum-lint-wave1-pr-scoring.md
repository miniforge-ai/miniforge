# fix: stratum-lint autofix for components/pr-scoring (Wave 1)

## Overview

Runs `stratum-lint --fix` (sha `80699e378cb8ebbb6daeb928431aa4a6b373c07e`)
over all 3 Clojure files (2 `src`, 1 `test`) in `components/pr-scoring`,
regrouping each file's defs under regenerated `;---- Layer N` headings and
tagging every def with `^{:stratum n}` metadata inferred from the real
same-file reference graph. No logic changes — headings, metadata, and def
order only.

## Motivation

`work/stratum-lint-baseline-2026-07-24.md`'s diagnosis found rule 210's
stratified-design headings had been cargo-culted across most of the tree
into decorative section banners that don't track a real dependency DAG.
`components/pr-scoring` carried 2 reported findings, both in `core.clj` —
`SL002` (a `Layer 0` heading repeated instead of incrementing) and `SL003`
(4 distinct decorative layers against the 3-layer budget) — and **zero
`SL001`** upward-reference findings, which is what puts it in the Wave 1
safe-to-autofix batch: no cycle/upward-call risk to reason about before
running the mechanical fixer.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/pr-scoring
```

All 3 files rewritten: `core.clj`, `interface.clj` (both `src`),
`core_test.clj` (`test`). `--fix` normalizes every file in the component,
not just the ones with findings — `interface.clj` and `core_test.clj` had
none of their own; both are pure pass-through/test files where the real
reference graph collapses to fewer layers than the old decorative banners
implied.

`core.clj` — the file the baseline flagged — resolves to **5 real layers**
(`subscriber-id` / `trigger-config-resource` / `default-scorer-fn` /
`emit-scored!` at 0; `load-default-triggers` / `handle-event!` / `stop!` at
1; `default-trigger-event-types` / `start!` at 2; `create` at 3; `attach!`
at 4), not the 4 distinct decorative headings the pre-fix banners showed.
`--fix`'s honest reference-graph computation surfaced one more real layer
than the old headings reported, not fewer — this file was under-counted,
not over-counted, by the cargo-cult banners.

`interface.clj` collapses to a single real layer (`0`): every def in it is
a pass-through alias into `core`, so none references another same-file def.
Its old banners (`Layer 1: Lifecycle`, `Layer 2: Extension points`) were
themselves decorative overcounts of a file with no internal reference
depth at all.

`core_test.clj` had no `Layer N`-pattern heading before the fix at all
(its old banners — `Helpers`, `Lifecycle`, `Emission` — are the same
full-width dashed comment style but omit the word "Layer", so
`stratum-lint`'s heading regex never matched them and the file was
silently skipped by the plain-lint scan, per the baseline doc's documented
limitation). `--fix` still processed it and assigned 3 real layers. Those
three old banners survived untouched as ordinary comments, now sitting
inside the corresponding real `Layer N` blocks (`Helpers` under `Layer 0`,
`Lifecycle` under `Layer 1`, `Emission` under `Layer 2`). Checked each: all
three still correctly describe the code immediately following them (no
"Layer N" claim to contradict, and no relocation puts one next to the
wrong group) — left in place rather than deleted or reworded.

Checked every changed file for the other known tool limitation — a
same-line trailing comment (`closing-form])  ; comment`) detached and
reattached next to the wrong def during reordering. None present in this
component; the one inline comment that exists (`pr-created-event`'s
`;; pr/created isn't an event-stream constructor yet...`) is already its
own indented line inside the function body, not a same-line trailing
comment on a closing form, and moved as a unit with the function.

## Testing Plan

1. Ran plain (non-`--fix`) lint before fixing: 2 findings (`SL002` and
   `SL003`, both on `core.clj`), 0 `SL001` — matches the baseline.
2. Ran `--fix`, then ran the identical `--fix` command a second time: no
   files reported as rewritten; diffed all 3 files byte-for-byte against
   their post-first-fix state — zero diff, confirming idempotency.
3. Read the full diff for all 3 changed files. Confirmed the only changes
   are heading placement, def reordering, and `^{:stratum n}` metadata —
   no executable code changed.
4. `clj-kondo --lint components/pr-scoring`: found 1 pre-existing warning
   (`unused binding stream` in `core_test.clj`'s `pr-created-event`),
   confirmed via `git stash` against the pre-fix file to already exist
   before this PR at a different line number (46 → 37, from reordering).
   Unrelated to stratum-lint, but cheap and risk-free to fold in here
   (Copilot review flagged it independently): renamed the unused param to
   `_stream` to document the intent — kept for call-site symmetry with the
   real event-stream producer signature, every call site passes `s`. No
   behavior change. Re-ran `--fix` after the rename: zero diff (no
   rewrite), confirming it didn't disturb the stratum computation.
   `clj-kondo` now reports 0 errors, 0 warnings.
5. Re-ran plain `stratum-lint` after the fix: `SL003` remains on
   `core.clj`, now reporting **5** real layers instead of the 4 distinct
   decorative ones originally flagged — surfaced by `--fix`'s honest
   reference-graph analysis, not a regression. Wave 2 scope.
6. Ran `ai.miniforge.pr-scoring.core-test` directly via `clojure -A:test
   -e`: 11 tests, 26 assertions, 0 failures, 0 errors.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; `core.clj`'s `SL003`
stays advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until
Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Precedent: same Wave 1 mechanical-fix pattern as `bb-config`, `logging`,
  `observer`, `patterns`, `dag-primitives`, and others already merged
- Follow-on: Wave 2 namespace split for
  `components/pr-scoring/src/ai/miniforge/pr_scoring/core.clj` (5 real
  layers, over the 3-layer budget)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 3 changed files; mechanical-only
- [x] Checked for same-line trailing-comment reattachment: none present
- [x] Checked for stale `Layer N`-labeled decorative banners contradicting
      the new real headings: none present (old non-"Layer" banners in
      `core_test.clj` remain accurate where they sit; left as-is)
- [x] `clj-kondo`: 0 errors, 0 warnings (one pre-existing unused-binding
      warning fixed in passing — `_stream` rename, no behavior change)
- [x] Component tests pass (11 tests, 26 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: `SL003` remains on `core.clj` (5 real
      layers, up from 4 decorative — documented, Wave 2 scope)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
