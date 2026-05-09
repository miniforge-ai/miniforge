<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix workflow leave-after-enter-failure and chain step observability

## Summary

Fix two generic Miniforge workflow runtime issues exposed by the
Thesium Career JD fixture path:

1. chain step results now retain the underlying workflow execution id
2. workflow execution no longer invokes a phase `:leave` function when
   the phase `:enter` step failed before phase context was established

Together these changes stop opaque follow-on failures and make chained
workflow failures inspectable at the exact step execution boundary.

## Changes

- `components/workflow/src/ai/miniforge/workflow/chain.clj`
  - retain `:step/execution-id` on each chain step result
- `components/workflow/src/ai/miniforge/workflow/execution.clj`
  - add `entered-phase-context?`
  - skip `execute-leave` when enter-phase failure produced only a
    failed error map and never established `:started-at` / `:result`
- `components/workflow/test/ai/miniforge/workflow/chain_events_test.clj`
  - assert step execution ids are retained on success and failure
- `components/workflow/test/ai/miniforge/workflow/run7_regression_test.clj`
  - add regression coverage proving leave is not called after an
    enter-phase exception that occurred before phase context existed

## Why

Before this fix, an enter-phase exception in a workflow step could be
followed by an unrelated `NullPointerException` from the phase's leave
handler, because leave tried to finalize a phase that had never
initialized `:started-at` or `:result`.

That turned the real failure into a misleading secondary crash.

Separately, chain results lacked the workflow execution ids needed to
inspect the exact failing step in Miniforge event streams.

## Verification

- `git diff --check`
- `bb test`

## Result

Miniforge now preserves the original enter-phase failure and exposes
the exact workflow execution id for each chained step, which gives
product CLIs enough information to point operators directly at the
right event stream for diagnosis.
