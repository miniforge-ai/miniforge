<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix workflow leave-after-enter-failure and Codex workflow observability

## Summary

Fix three generic Miniforge workflow runtime issues exposed by the
Thesium Career JD fixture path:

1. chain step results now retain the underlying workflow execution id
2. workflow execution no longer invokes a phase `:leave` function when
   the phase `:enter` step failed before phase context was established
3. sandboxed Codex library runs now seed a writable runtime
   `CODEX_HOME` instead of assuming `~/.codex` is writable

Together these changes stop opaque follow-on failures and make chained
workflow failures inspectable at the exact step execution boundary.

## Changes

- `components/workflow/src/ai/miniforge/workflow/chain.clj`
  - retain `:step/execution-id` on each chain step result
- `components/workflow/src/ai/miniforge/workflow/execution.clj`
  - add `entered-phase-context?`
  - skip `execute-leave` when enter-phase failure produced only a
    failed error map and never established `:started-at` / `:result`
- `components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj`
  - seed a writable runtime `CODEX_HOME` under `java.io.tmpdir`
  - copy auth/config seed files from the user's configured Codex home
  - pass backend-specific process env through both streaming and
    non-streaming CLI execution
- `components/workflow/test/ai/miniforge/workflow/chain_events_test.clj`
  - assert step execution ids are retained on success and failure
- `components/workflow/test/ai/miniforge/workflow/run7_regression_test.clj`
  - add regression coverage proving leave is not called after an
    enter-phase exception that occurred before phase context existed
- `components/llm/test/ai/miniforge/llm/args_fn_test.clj`
  - cover runtime `CODEX_HOME` seeding

## Why

Before this fix, an enter-phase exception in a workflow step could be
followed by an unrelated `NullPointerException` from the phase's leave
handler, because leave tried to finalize a phase that had never
initialized `:started-at` or `:result`.

That turned the real failure into a misleading secondary crash.

Separately, chain results lacked the workflow execution ids needed to
inspect the exact failing step in Miniforge event streams.

And once the Thesium workflow boundary was correctly selecting Codex as
an injected backend, Miniforge's library execution path still failed in
sandboxed runs because the Codex CLI tried to write under `~/.codex`.

## Verification

- `git diff --check`
- `clojure -M:test -e "(require 'ai.miniforge.llm.args-fn-test 'ai.miniforge.workflow.chain-events-test 'ai.miniforge.workflow.run7-regression-test) (let [result (clojure.test/run-tests 'ai.miniforge.llm.args-fn-test 'ai.miniforge.workflow.chain-events-test 'ai.miniforge.workflow.run7-regression-test)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))"`

## Result

Miniforge now preserves the original enter-phase failure, exposes the
exact workflow execution id for each chained step, and lets Codex run
as a real injected library backend under sandboxed workflow execution
instead of failing on local session-state writes.
