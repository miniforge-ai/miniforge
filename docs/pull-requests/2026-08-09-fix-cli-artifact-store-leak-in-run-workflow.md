<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli): close the artifact store on every exit path of run-workflow

## Overview

`run-workflow!` now releases the workflow's transit artifact store in its
`finally` block, once, on every exit path. Previously the close lived only
on the normal-completion branch, so any pipeline throw leaked the store.

This is the follow-up flagged in the "Callers — checked" section of
`2026-08-09-fix-cli-artifact-store-leak-on-sandbox-failure.md`, which fixed
the same leak shape in `execute-with-events` (the spec-driven path). The
two entry points now use the same pattern.

## Motivation

`run-workflow!` opens the store with `execution/create-artifact-store`,
hands it to `execution/execute-workflow-pipeline`, and closed it only on
the success branch immediately after the pipeline returned. The `finally`
block (manifest `:cancelled` fallback, progress cleanup, GC enqueue) never
closed it, and the outer `catch Exception` rethrows without closing. Every
pipeline exception — and every abort between store creation and the
success branch — leaked one open transit store.

## Layer

CLI base only: `bases/cli/src/ai/miniforge/cli/workflow_runner.clj` plus a
new test namespace. No component interfaces change.

## Changes in Detail

- `run-workflow!`: the `(execution/close-artifact-store artifact-store)`
  call moves from the pipeline-success branch into the `finally` block,
  after `progress-cleanup` and before the GC enqueue. One unconditional
  call rather than a per-branch duplicate: `close-artifact-store` is
  nil-safe and swallows close-time exceptions, so a single call in
  `finally` covers completion and the throw path without double-closing
  or masking an in-flight exception. On the success path the close now
  happens after `print-result` instead of before manifest marking;
  nothing in between reads the store.
- `bases/cli/test/ai/miniforge/cli/workflow_runner/store_lifecycle_test.clj`
  (new): drives the real `run-workflow!` with collaborators stubbed via
  `with-redefs`, recording every store handed to
  `execution/close-artifact-store` — the same recording approach as
  `execution_test.clj`'s `execute-recording-closes` harness. Two tests:
  the success path closes the sentinel store exactly once; a throwing
  pipeline still closes it before the rethrow propagates. A
  `:dashboard-url` opt keeps the event stream nil so the manifest,
  progress, and shutdown helpers take their documented nil no-op paths.

## Why a direct test of run-workflow! is possible now

`gc_integration_test.clj`'s docstring records that requiring
`workflow_runner.clj` used to start non-terminating background threads at
namespace-load time, hanging the test JVM. That predates the rule-210
split: the process-scoped singletons now live in `control.clj` as lazily
initialized `defonce` atoms. Verified empirically — requiring the
namespace in a fresh JVM creates zero new threads. The stale docstring is
left for a follow-up rather than widening this diff.

## Callers — checked

`run-workflow!`'s callers (cli main, commands, chain) consume only the
result map; none receive or touch the artifact store, so closing in
`finally` cannot close a store still in use. `run-workflow-from-spec!`
delegates its store's release to `execute-with-events`, fixed in the
sibling PR.

## Standards Audit

- Stratified design (001/210): no stratum changes in the source file;
  the moved call stays inside the same stratum-1 fn. Test namespace
  layers: recording harness at 0, tests at 1 — in-namespace dependencies
  flow downward.
- Header rule (810): Apache header on the new test file.
- Exception handling (211): no new catches in source; the test uses
  slingshot `try+`/`catch Object` to observe the rethrow, matching
  `execution_test.clj`.
- Test fixtures match the producer's shape
  (feedback_test_fixtures_match_production_shape): the stubbed pipeline
  returns an `:execution/status` result like the real pipeline, and the
  success assertion reads `:execution/status`.

## Testing Plan

- `clojure -M:dev:test` on the new namespace: 2 tests, 5 assertions,
  green.
- Mutation-checked rather than trusting green: reverting the source fix
  (close on the success branch only, as on main) fails
  `run-workflow!-closes-store-when-pipeline-throws-test` with an empty
  recorded-close vector, confirming the test binds to the release
  behavior on the throw path.
- `bb lint:clj` on both staged files: 0 errors, 0 warnings.
- Pre-commit hook (commit budget, structure, lint, format, smoke tests)
  runs at commit time; full suite in CI.
