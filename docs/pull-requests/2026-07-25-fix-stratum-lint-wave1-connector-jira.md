# fix: stratum-lint autofix for components/connector-jira (Wave 1)

## Overview

Runs `stratum-lint --fix` over the whole `connector-jira` component
(`src` and `test`) to replace decorative `Layer N` section headings with
headings that reflect each file's real same-file reference graph, and
tags every `def`/`defn`/`defrecord`/`deftest` with `^{:stratum n}`
metadata. Purely mechanical — no logic changes. One of the per-component
Wave 1 PRs from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

`work/stratum-lint-baseline-2026-07-24.md` found rule 210's `Layer N`
heading convention (`standards/miniforge/languages/clojure.mdc`) cargo-culted
across most of the codebase — headings repeated as visual section breaks
instead of one heading per real abstraction stratum. `connector-jira`
carried exactly 1 finding: `SL003` in `test/.../impl_test.clj` (7 nominal
`Layer` headings, one per `deftest` group, versus a budget of 3) — and
zero `SL001` (upward-reference) findings, which is why the baseline named
it a Wave 1 target: no cycle/upward-call risk to reason about before
running the mechanical fixer.

## Changes in Detail

Ran, after resetting the branch onto current `main` (pin `80699e378c` in
`tasks/stratum.clj`):

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/connector-jira
```

All 8 `.clj` files rewritten (6 `src`, 2 `test`) — including files with
zero prior findings; `--fix` normalizes those too. Diff stat: 8 files
changed, 146 insertions(+), 153 deletions(-).

- `core.clj`, `messages.clj` — single-def files, no reordering, just a
  `Layer 0` heading and `^{:stratum 0}` metadata added.
- `interface.clj` — 2 decorative headings (`Layer 0`/`Layer 1`) collapsed
  to 1 real layer; the schema re-export and the factory/metadata defs
  don't actually reference each other.
- `schema.clj` — decorative 3-heading structure regrouped into the real
  4-layer chain: schemas → `validate`/`validate!` (Layer 0) → `resource->schema`
  registry + `validate-response` (Layer 1) → `record-schema` (Layer 2) →
  `validate-records` (Layer 3).
- `resources.clj` — regrouped into a real 4-layer chain:
  `resource-path`/URL builders (Layer 0) → `load-resources`/`build-query-params`
  (Layer 1) → `jira-resources` delay (Layer 2) → `get-resource`/`resource-schemas`
  (Layer 3).
- `impl.clj` — regrouped into a real 4-layer chain: boundary/auth
  helpers + `do-checkpoint` (Layer 0) → handle-registry wrappers +
  `do-request` (Layer 1) → `fetch-all-pages`/`do-connect`/`do-close`/`do-discover`
  (Layer 2) → `do-extract` (Layer 3).
- `test/anomaly/jira_anomaly_test.clj` — single decorative heading
  collapsed to `Layer 0`, no reordering.
- `test/impl_test.clj` — the file with the original finding: 7 decorative
  per-`deftest`-group headings (`Layer 0`–`Layer 6`) collapsed to 1 real
  layer (`deftest` forms don't reference each other); `deftest` order in
  the file is unchanged.

No same-line trailing comments existed in any of these 8 files before the
fix (checked pre-fix content for `) ;` / `] ;` patterns), so the known
same-line-trailing-comment reattachment issue does not apply here — no
hand fix-up was needed.

## Testing Plan

1. **Idempotency**: ran `--fix` a second time over the whole component;
   `diff -r` against a copy taken after the first pass showed zero diff.
2. **Diff review**: read every changed file's full diff (not just
   `--stat`). Confirmed the only changes are heading text, `^{:stratum n}`
   metadata, and def/deftest reordering — no logic or assertion changes.
3. **`clj-kondo --lint components/connector-jira`**: 0 errors, 0 warnings.
4. **Plain (non-`--fix`) re-lint** after the fix:

   ```bash
   bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface components/connector-jira
   ```

   3 `SL003` findings remain — **expected, out of scope for this PR**:
   `impl.clj` (4 real layers), `resources.clj` (4 real layers), `schema.clj`
   (4 real layers), all over the 3-layer budget. None of these 3 files
   had any finding before this fix — their old, partially-honest 2-heading
   structure under-reported real depth. `--fix`'s reference-graph inference
   surfaced the true depth; the code's actual structure did not change.
   All 3 need a real namespace split, tracked as Wave 2 work
   (`work/stratum-lint-baseline-2026-07-24.md`). Zero `SL001`/`SL002`/`SL004`
   remain.
5. **Component tests**: `ai.miniforge.connector-jira.impl-test` and
   `ai.miniforge.connector-jira.anomaly.jira-anomaly-test`, run via
   `clojure -A:dev:test`. 24 tests, 71 assertions, 0 failures, 0 errors.

## Deployment Plan

Merges to `main` like any other component change. Comment/metadata/reorder
only — no runtime behavior change, no migration. Committed with
`MINIFORGE_STRATUM_BUDGET_MODE=warn` for the 3 pre-existing over-budget
files this PR surfaces but doesn't create or worsen.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for `impl.clj`, `resources.clj`,
  `schema.clj` (all now `SL003`, 4 real layers each)
- Same shape as other Wave 1 component PRs (e.g. #1461, #1462, #1463,
  #1464, #1467)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Idempotency verified directly (two `--fix` passes, zero diff)
- [x] Diff read in full for every changed file, not just `--stat`
- [x] No same-line trailing comments existed pre-fix; no hand fix-up
      needed
- [x] `clj-kondo --lint` clean (0 errors, 0 warnings)
- [x] Plain lint re-run post-fix: 3 expected `SL003` findings remain
      (documented above, tracked as Wave 2); zero `SL001`/`SL002`/`SL004`
- [x] Component test suite run: 24 tests / 71 assertions, 0 failures/errors
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
