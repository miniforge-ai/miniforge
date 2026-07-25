# fix: stratum-lint autofix for components/connector-linter (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/connector-linter` (`src` + `test`)
to replace the one decorative `Layer N` heading with headings that reflect
each file's real same-file reference graph, and tags every `def`/`defn` with
`^{:stratum n}` metadata. Purely mechanical: no logic changes. One of the
per-component Wave 1 PRs from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

The baseline audit found `connector-linter` carrying exactly one finding —
`SL002` in `etl.clj` (a `Layer 2` heading reused instead of strictly
increasing) — and zero `SL001` (upward-reference) findings, which is
precisely the Wave 1 selection criterion: no cycle/upward-call risk to
reason about before running the mechanical fixer.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "80699e378cb8ebbb6daeb928431aa4a6b373c07e" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/connector-linter
```

All 4 `.clj` files in the component were rewritten — `etl.clj`,
`interface.clj`, `runner.clj`, `etl_test.clj` — including `interface.clj`,
which had no `Layer N` heading at all before this fix (silently skipped by
the plain lint check, not counted in the baseline's "1 finding"; `--fix`
normalizes every file regardless).

`etl.clj`'s real reference graph turned out to be 4 layers deep once
inferred from actual same-file calls (`apply-mapping` → `extract-records` /
`get-mapping` → `mappings` → `mappings-resource`), one over the 3-layer
budget — a new `SL003` this fix surfaces but cannot resolve itself (needs an
actual namespace split, Wave 2 scope, not attempted here).

No same-line trailing comments existed in any of the 4 files before this
fix (checked directly against the pre-fix content), so the comment-
reattachment risk this wave's playbook warns about does not apply here —
the only comments in these files are section-banner style, and each stayed
attached to the same def/group it always described.

## Testing Plan

1. Plain (non-`--fix`) lint before the fix: reproduced the baseline's exact
   single finding — `SL002` at `etl.clj:155`.
2. Ran `--fix`, then ran the identical `--fix` command a second time and
   diffed the component's files before/after that second pass: zero diff.
   Idempotency verified directly, not assumed.
3. Read the full diff for all 4 changed files. Confirmed the only changes
   are heading text, `^{:stratum n}` metadata, and def reordering — no
   logic changes, no comment misattachment.
4. `clj-kondo --lint components/connector-linter`: 0 errors, 0 warnings.
   `record->violation` originally had an unused `column` binding (pre-
   existing, confirmed via `git stash` comparison against the pre-fix
   file — same warning present before this PR) — removed during review,
   along with a stale section header and two stale docstring layer
   summaries Copilot caught on later passes. See commit history for the
   review-fix commits.
5. Plain lint re-run post-fix: 1 `SL003` remains (`etl.clj`, 4 real layers
   against the 3-layer budget) — Wave 2 scope, documented above.
6. Ran the component's test namespace directly:
   `clojure -A:dev:test -e "(require 'ai.miniforge.connector-linter.etl-test) (clojure.test/run-tests 'ai.miniforge.connector-linter.etl-test)"`
   — 11 tests, 33 assertions, 0 failures, 0 errors. (The component-local
   `deps.edn` alone can't resolve the classpath for this — it pulls in
   `compliance-scanner` → `policy-pack`, whose `deps.edn` doesn't declare a
   `knowledge` local/root that `policy-pack.loader` requires, a pre-existing
   gap unrelated to this change; ran from the repo root against the
   workspace `:dev:test` aliases instead, which resolve correctly.)

## Deployment Plan

Merges to `main`. No runtime behavior change — headings, metadata, and def
order only.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for `etl.clj` (now `SL003`, 4 real
  layers)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Idempotency verified directly (two `--fix` passes, zero diff)
- [x] Full diff read for all 4 changed files; no comment misattachment
      (none existed pre-fix)
- [x] `clj-kondo` clean (0 errors, 0 warnings) — one pre-existing unused
      binding removed during review
- [x] Plain lint re-run post-fix: `SL003` remains on `etl.clj`, documented
      as Wave 2 scope
- [x] Tests pass: 11 tests / 33 assertions, 0 failures, 0 errors
- [x] No `--no-verify`; commit-budget / stratum-budget overrides used with
      recorded rationale
