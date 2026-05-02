<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix/dag-executor-event-soft-dep-race

## Summary

Fixes a flaky `dag-executor` event-emission path that was failing under the
repo's parallel pre-commit test run.

The issue was in
[state.clj](../../components/dag-executor/src/ai/miniforge/dag_executor/state.clj):
`emit-task-state-event!` was resolving `event-stream` vars with raw
`requiring-resolve` on every call and swallowing failures. Under the full
parallel suite, that could race during namespace loading and silently skip
publishing `:task/state-changed`.

This PR switches that path to cached delayed soft-dependency resolution, which
matches the pattern already used in other parts of the repo.

## Key Change

- added cached delayed soft-dep resolution for:
  - `ai.miniforge.event-stream.interface/publish!`
  - `ai.miniforge.event-stream.interface/task-state-changed`
- updated `emit-task-state-event!` to use the cached vars instead of raw
  `requiring-resolve`

## Why This Matters

This is the kind of repo-health issue that blocks unrelated PRs in pre-commit.
The taxonomy branch for dependency attribution surfaced it, but the bug is in
`dag-executor` and should be fixed independently.

## Validation

- `clj-kondo --lint components/dag-executor/src/ai/miniforge/dag_executor/state.clj`
- `clojure -M:dev:test -e \"(require 'clojure.test 'ai.miniforge.dag-executor.state-test) ...\"`
- `bb test components/dag-executor`
- full `bb pre-commit`
