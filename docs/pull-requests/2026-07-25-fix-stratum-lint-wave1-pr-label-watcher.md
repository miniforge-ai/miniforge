# fix: stratum-lint autofix for components/pr-label-watcher (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/pr-label-watcher` (`src` +
`test`) to replace decorative `Layer N` section banners with headings
and `^{:stratum n}` metadata derived from the file's actual same-file
reference graph. Purely mechanical: no logic changes. One of the
smaller per-component Wave 1 PRs from
`work/stratum-lint-baseline-2026-07-24.md` (batch 3).

## Motivation

`pr-label-watcher` carried exactly two findings under the baseline's
cargo-cult diagnosis, both `SL002` in `core.clj` (a repeated `Layer 0`
banner used three times as a visual section break instead of once per
real stratum). Zero `SL001` findings, so no upward-reference/cycle risk
to reason about before running the mechanical fixer — matches the
baseline's Wave 1 batch criteria.

## Changes in Detail

Ran, over the whole component, at the pin declared in `tasks/stratum.clj`
(`80699e378cb8ebbb6daeb928431aa4a6b373c07e`):

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/pr-label-watcher
```

3 files rewritten: `core.clj`, `interface.clj` (both `src`), and
`core_test.clj` (`test`) — `--fix` normalizes every file in the
component, not just the one with a finding. `interface.clj` had no
`Layer` heading at all before this (the tool silently skips headerless
files on a plain lint pass); it now carries `Layer 0` and per-`def`
`^{:stratum 0}` metadata, since every re-export in that file is a bare
alias with no same-file dependency on another def in the file.
`core.clj`'s three real layers (0–2) now match the file's actual call
chain: `matching-labels`/`workflow-applies?`/`ancestor-via-shell`/
`workflow-summary` at Layer 0, `load-registry`/`make-ancestor?-fn`/
`build-match-payload` at Layer 1 (each composes a Layer-0 def), and
`match` at Layer 2 (composes both).

`core_test.clj`'s layers are **not** a mirror of `core.clj`'s — each
file's strata come from its own same-file reference graph only, computed
independently. In the test file: the fixtures (`dogfood-fix-action`,
`wf-old`, `wf-recent`, `wf-no-base-sha`, `merge-event`,
`ancestry-table-fn`) sit at Layer 0; `test-registry`, which references
`dogfood-fix-action`, sits at Layer 1; each `deftest` then lands one
layer above the highest-layer same-file fixture/helper it references (or
at Layer 0 if it references none — most tests call `sut/...` functions,
which are cross-namespace and don't count). This is why the layers don't
line up with `core.clj` at all: `load-registry` is Layer 1 in `core.clj`
but its tests are Layer 0 in `core_test.clj` (they call no same-file
fixture); `matching-labels` is Layer 0 in `core.clj` but its tests are
Layer 2 (they reference `test-registry`, which is Layer 1). The one
place layer numbers happen to coincide — `match` at Layer 2 in both
files — is coincidental: both files' own dependency chains happen to run
3 deep, not a deliberate correspondence. No line of executable code
changed; diffs are heading text, metadata, and def/deftest reordering
only.

One hand-fix beyond the mechanical run, in `core_test.clj`: five
old single-semicolon `;---- Layer N: <description>` banners (grouping
tests by which `core.clj` function they cover, e.g. `Layer 1: ancestor
predicate factory`) survived the `--fix` pass untouched — a documented
tool limitation, since the tool only recognizes bare `Layer N` headings
as real, not the colon-plus-description variant. After reordering, four
of the five now sat under a *different* real `Layer N` heading than the
number they claimed (e.g. `;---- Layer 1: ancestor predicate factory`
ended up positioned under tests the fixer placed at real `Layer 0`) —
actively misleading, not merely stale. Reworded each of the five to drop
the now-wrong `Layer N:` prefix, keeping the descriptive fragment
(`;; ancestor predicate factory`, `;; workflow-applies?`, `;;
matching-labels`, `;; match (top-level)`, `;; load-registry`) since it
still names a real, useful test grouping — matching the plain
double-semicolon description style `--fix` itself used for `core.clj`'s
surviving comments (e.g. `;; Ancestry predicate`). Re-ran `--fix` after
the edit: zero diff, confirming the hand-fix didn't destabilize the
tool's own output.

A second hand-fix, in `core.clj`: `--fix` left a single Layer-1 group
comment, `;; Pure registry helpers`, ahead of all three Layer-1 defs
(`load-registry`, `make-ancestor?-fn`, `build-match-payload`) — accurate
for the first, wrong for the other two (`make-ancestor?-fn` builds a
git-ancestry/shell predicate; `build-match-payload` assembles the output
payload, neither is registry-related). Split it into three group
comments, one per def, matching this same file's own Layer-0 convention
(which already carries one short comment per def-group: `;; Tuning
constants + resource paths`, `;; Ancestry predicate`, `;; Match payload
assembly`): `;; Registry loading`, `;; Ancestor predicate factory`, `;;
Payload assembly`. Re-ran `--fix` after the edit: zero diff.

No `SL003` (over-budget) risk here: `core.clj` tops out at 3 real
layers, exactly at budget.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced
   the baseline's two `SL002` findings exactly (`core.clj:50`,
   `core.clj:80`).
2. Ran `--fix`, then a second `--fix` pass immediately after — zero
   diff, confirms idempotency.
3. Read the full diff for all 3 changed files. Found the stale-banner
   contradiction described above in `core_test.clj`; hand-fixed it, then
   ran `--fix` a third time — zero diff, confirms the hand-fix is stable
   too. No same-line trailing comment was displaced onto the wrong def
   (this component has none of that shape).
4. `clj-kondo --lint components/pr-label-watcher`: 0 errors, 0 warnings.
5. Re-ran plain `stratum-lint` after the fix: zero findings of any kind
   (`SL001`–`SL004` all clear, including `SL003` — no over-budget file
   surfaced).
6. Ran `ai.miniforge.pr-label-watcher.core-test` directly via
   `clojure -A:test -e`: 18 tests, 36 assertions, 0 failures, 0 errors.
7. After review flagged the `core.clj` Layer-1 header and the
   `core_test.clj` regrouping claim (below), re-ran `--fix` again post
   hand-fix: zero diff. Re-ran `clj-kondo`, plain `stratum-lint`, and the
   test suite: unchanged — 0/0, zero findings, 18 tests/36 assertions.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1, batch 3)
- No Wave 2 follow-on needed — no `SL003` finding remains.

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Repeated `--fix` passes confirm idempotency (zero diff) after each
      of this PR's two hand-fixes and one doc correction
- [x] Diff read in full for all 3 changed files; mechanical except two
      hand-fixes (stale, now-contradictory `Layer N:` banner text in
      `core_test.clj`; an inaccurate multi-def group comment in
      `core.clj`) — no code or test-assertion changes
- [x] `clj-kondo` clean before/after (0 errors, 0 warnings)
- [x] Component tests pass (18 tests, 36 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings, no `SL003` remains
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
