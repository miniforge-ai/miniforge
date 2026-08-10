<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli): close the artifact store on every exit path of execute-with-events

## Overview

`execute-with-events` now releases the workflow's transit artifact store in
its `finally` block, once, on every exit path. Previously the close lived
only on the normal-completion branch, so two paths leaked the store.

## Motivation

`run-workflow-from-spec!` opens the store with
`execution/create-artifact-store` *before* `sandbox/setup-sandbox-context`
runs, then hands it to `execute-with-events`. Inside, only the pipeline
branch closed it after publishing the completion event. Two paths never
did:

- The sandbox-setup-failure branch (`:sandbox-error` set in context):
  published the failure result and returned with the store still open.
  Every failed container sandbox setup leaked one open transit store.
- The `catch Object` path: published the failure event and rethrew via
  `throw+` without closing. The caller does not close it either — the
  store's lifecycle ends inside `execute-with-events` or not at all.

## Layer

CLI base only: `bases/cli/src/ai/miniforge/cli/workflow_runner/execution.clj`
plus its (new) test namespace. No component interfaces change.

## Changes in Detail

- `execute-with-events`: the `(close-artifact-store artifact-store)` call
  moves from the pipeline-success branch into the `finally` block, after
  the not-completed `:cancelled` publish and before `sandbox-cleanup`.
  One unconditional call rather than a per-branch duplicate with a
  closed-twice guard: `close-artifact-store` is already nil-safe and
  swallows close-time exceptions, so a single call in `finally` covers
  completion, sandbox-setup failure, and the rethrow path without ever
  double-closing or masking an in-flight exception.
- `bases/cli/test/ai/miniforge/cli/workflow_runner/execution_test.clj`
  (new namespace — none existed for this file): an
  `execute-recording-closes` harness runs `execute-with-events` with a
  sentinel store, stubbing `artifact/close-store` to record what it
  receives and silencing the lifecycle/display side effects. Three tests:
  sandbox-failure branch closes the store and reports
  `:sandbox-setup-failed` without invoking the pipeline; a throwing
  pipeline still closes the store before the rethrow propagates; the
  success path closes exactly once (guards against a duplicate close
  reappearing alongside the `finally`).

## Callers — checked

`run-workflow-from-spec!` is the only caller. Nothing after
`execute-with-events` returns touches the store (`spec-kanban` moves and
the meta-loop trigger consume only the result), so closing in `finally`
cannot close a store still in use.

`run-workflow!` (the non-spec entry point in `workflow_runner.clj`) has
the same leak shape on its own throw path — its `finally` never closes
the store it opened. Out of scope for this PR; flagged as a follow-up
task rather than widening this diff.

## Standards Audit

- Stratified design (001/210): no stratum changes; `close-artifact-store`
  (stratum 0) was already below `execute-with-events` (stratum 1). Test
  namespace layers: harness at 0, sandbox helper and direct tests at 1,
  sandbox-branch test at 2 — in-namespace dependencies flow downward.
- Header rule (810): Apache header on the new test file.
- Exception handling (211): no new catches; the test uses slingshot
  `try+`/`catch Object` to observe the rethrow, matching sibling tests.
- Test fixtures match the producer's shape
  (feedback_test_fixtures_match_production_shape): the sandbox-error
  context uses the same `{:sandbox-error {:error ...}}` shape
  `setup-sandbox-context` produces and the same `:errors` vector shape
  the branch itself constructs.

## Testing Plan

- `clojure -M:dev:test` on the new namespace: 3 tests, 9 assertions,
  green.
- Mutation-checked rather than trusting green: deleting the `finally`
  close fails all three tests (each asserts the recorded close vector),
  confirming the tests bind to the release behavior, not incidental
  output.
- `bb lint:clj` on both staged files: 0 errors, 0 warnings.
- Pre-commit hook (commit budget, structure, lint, format, smoke tests)
  runs at commit time; full suite in CI.
