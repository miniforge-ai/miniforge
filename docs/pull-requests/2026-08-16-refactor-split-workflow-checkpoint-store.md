<!--
  Title: Split workflow/checkpoint_store.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(workflow): split checkpoint_store.clj (rule 210)

## Overview

Splits path resolution and record building out of
`ai.miniforge.workflow.checkpoint-store` into two new sibling
namespaces, `checkpoint-store-paths` and `checkpoint-store-records`,
clearing a pre-existing stratum-lint SL003 finding: the combined
namespace measured 5 real layers against rule 210's budget of 3.

Pure code motion. No behavior change — every moved function keeps its
body byte-for-byte, and the only edits to moved code are the
namespace-qualified call sites the move requires.

## Motivation

`bb lint:stratum` lints changed files only, so the violation was
dormant until someone touched the file — at which point a one-line
edit inherited the whole finding. PR #1800 hit exactly this: adding
`:execution/acting` to `persisted-execution-keys` had to be committed
with `MINIFORGE_STRATUM_BUDGET_MODE=warn`.

The file's own docstring already named the seam and stated that
renumbering banners would not close it: the chain (persist → manifest
→ per-phase path → atomic write → path normalization) is real, so the
fix is decomposition.

## Changes in Detail

Three namespaces, each a distinct question about a checkpoint:

- **`checkpoint_store_paths.clj` (new, 3 layers) — where it goes.**
  The filename/option-key constants, `normalize-checkpoint-root`,
  `workflow-checkpoint-dir`, `default-checkpoint-root`,
  `machine-snapshot-path`, `manifest-path`, `phase-checkpoints-dir`,
  `resolve-checkpoint-root`, `phase-checkpoint-path`. No file is
  opened here; callers get a path string back.
- **`checkpoint_store_records.clj` (new, 2 layers) — what it
  contains.** `persisted-execution-keys`, `ordered-phase-ids`,
  `active-or-last-phase`, `build-machine-snapshot`,
  `build-phase-checkpoint`, `build-manifest`, and the private
  `current-checkpoint-timestamp`. Pure projections of an execution
  context. The allowlist sits with `build-machine-snapshot`, the one
  function that applies it.
- **`checkpoint_store.clj` (3 layers) — moving it between memory and
  disk.** `temp-file-suffix`, the private `read-edn-file` and
  `write-edn-atomically!`, and the two public operations
  `load-checkpoint-data` and `persist-execution-state!`.

Dependencies point one way, with no cycles. `checkpoint-store`
requires both new namespaces; `checkpoint-store-records` requires
`checkpoint-store-paths` (`build-manifest` records the paths it
indexes); `checkpoint-store-paths` requires neither of the other two.

`load-checkpoint-data` and `persist-execution-state!` stay defined
(as `defn`, not re-export `def`) in `checkpoint-store`, so every
existing caller — `workflow.interface.checkpoints`, `runner`,
`dag-resilience` — and every `with-redefs` in the test suite still
targets a real var in the namespace it already named. No re-export
aliases were introduced anywhere in this split; the three call sites
that used a moved var were repointed instead:

- `context.clj`: `resolve-checkpoint-root` ×2, now via
  `checkpoint-store-paths` (its only use of the store — the
  `checkpoint-store` require is gone).
- `checkpoint_store_test.clj`: `machine-snapshot-path` ×1.
- `runner_test.clj`: `build-machine-snapshot` ×2.

Deliberately **not** in scope, to keep the move behavior-preserving:
the `(or (:k m) {})` map-access forms in `build-manifest` and
`persist-execution-state!` that rule 106 would rewrite to
`(get m :k {})`. Those two forms differ when a key is present with an
explicit `nil` (EDN read back off disk can produce that), so the
rewrite is a semantic change and belongs in its own PR.

## Testing Plan

- `stratum-lint` clean (exit 0, no findings) on all six touched files,
  which is the finding this PR exists to clear.
- `clj-kondo` clean: 0 errors, 0 warnings.
- `bb poly:check`: OK.
- Direct namespace verification — `checkpoint-store-test` and
  `dag-resilience-resume-test`, with `runner-test` loaded to prove it
  compiles: 7 tests, 27 assertions, 0 failures, 0 errors.
- `bb test:all` (full brick + integration suite, not
  `check:affected-tests`): store namespace, so dependent bricks are
  out of the affected-tests scope.

## Deployment Plan

No deployment step. Library-internal refactor; no schema, config, or
on-disk checkpoint format change. Checkpoints written before this
change are read by the same code path afterwards.

## Related Issues/PRs

- Blocks/conflicts with #1800 (Ariadne 3b, open): it adds
  `:execution/acting` plus a docstring to `persisted-execution-keys`.
  That allowlist now lives in
  `components/workflow/src/ai/miniforge/workflow/checkpoint_store_records.clj`;
  the 7-line hunk moves there verbatim on rebase. Its context.clj and
  test changes are untouched by this PR.
- Part of the stratum-lint rule-210 remediation program.

## Checklist

- [x] SL003 cleared without `MINIFORGE_STRATUM_BUDGET_MODE=warn`
- [x] `persisted-execution-keys` contents unchanged
- [x] Public entry points (`load-checkpoint-data`,
      `persist-execution-state!`) still `defn` in `checkpoint-store`
- [x] No re-export `def` aliases introduced
- [x] Full test suite run, not affected-tests
