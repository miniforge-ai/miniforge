# fix: bump stratum-lint pin for the SL008 reader-cond guard

## Overview

Bumps `tasks/stratum.clj`'s pinned `stratum-lint` sha from `14965e1`
(current on `main`) to `bef8657`, which includes
[stratum-lint#15](https://github.com/miniforge-ai/stratum-lint/pull/15):
a `defn`/`defn-` written entirely inside a top-level
`#?(:bb ... :clj ...)` reader-conditional is invisible to `--fix`'s
reference graph (rewrite-clj tags the whole form `:reader-macro`, not
`:list`), so a same-file caller could get relocated *before* it,
producing a silent forward reference. `--fix` now refuses with a new
SL008 finding and leaves the file untouched when it detects this shape,
instead of silently emitting broken output.

## Motivation

Found during Wave 1 batch 4's `artifact` component PR:
`create-datalevin-store` is defined only inside a `#?(:bb ... :clj
...)` form, and `create-store` (a plain top-level `defn`) calls it.
`--fix` moved the whole reader-conditional to the file's appendix
(after every layered section), placing `create-store` — which now got
a real stratum — *before* its callee. `artifact`'s Wave 1 PR worked
around this locally (moved the reader-conditional inside the function
body) and flagged it as a tool defect. A repo-wide grep found the same
shape already present in `components/bb-test-runner/src/ai/miniforge/bb_test_runner/core.cljc`
(`classpath-test-roots` referenced by `run-all`), which would have hit
the identical silent corruption on its own future Wave 1 pass.

## Changes in Detail

- `tasks/stratum.clj`: `stratum-lint-deps`'s pinned sha,
  `14965e1` → `bef8657`.

## Testing Plan

Confirmed the sha resolves via `bb -Sdeps` (ran the actual
`stratum-lint.interface` invocation, not just a deps-resolve check).
`bb pre-commit` passes clean.

## Deployment Plan

Merges to `main` immediately. Follow-on: `artifact` and
`bb-test-runner`'s eventual Wave 1 passes will hit SL008 on any
remaining reader-conditional-wrapped def referenced elsewhere in the
same file, and need the same hand-restructure `artifact` already
applied before `--fix` can run cleanly.

## Related Issues/PRs

- Fix consumed: [stratum-lint#15](https://github.com/miniforge-ai/stratum-lint/pull/15)
- Found via: `components/artifact`'s Wave 1 batch 4 PR
- Also affects: `components/bb-test-runner` (confirmed, not yet fixed)
- Part of: `work/stratum-lint-baseline-2026-07-24.md`, Wave 1 batch 4

## Checklist

- [x] Sha resolves via `bb -Sdeps`
- [x] Pre-commit hook passes clean
