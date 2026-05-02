<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor/fsm-cleanup-final

## Summary

Removes the remaining live runtime dependency on `ai.miniforge.workflow.state`
outside the legacy compatibility namespace itself.

`workflow.comparison` now derives duration directly from execution timestamps,
so the old state namespace is no longer on an active runtime path after the FSM
refactor.

## Key Changes

- removed the `workflow.state` dependency from
  [comparison.clj](/private/tmp/mf-fsm-cleanup-final/components/workflow/src/ai/miniforge/workflow/comparison.clj)
- added direct coverage in
  [comparison_test.clj](/private/tmp/mf-fsm-cleanup-final/components/workflow/test/ai/miniforge/workflow/comparison_test.clj)

## Validation

- `clj-kondo --lint components/workflow/src/ai/miniforge/workflow/comparison.clj
  components/workflow/test/ai/miniforge/workflow/comparison_test.clj`
- `clojure -M:dev:test -e "(require 'ai.miniforge.workflow.comparison-test)(let [result (clojure.test/run-tests
  'ai.miniforge.workflow.comparison-test)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))"`
