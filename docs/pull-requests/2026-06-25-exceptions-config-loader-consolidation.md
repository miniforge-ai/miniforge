<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Exceptions Config Loader Consolidation

## Summary

Consolidates duplicated fail-fast EDN config resource loaders into the shared
`ai.miniforge.config.interface/load-config-resource` boundary.

## Changes

- Replaces component-local `load-config` helpers in operator, orchestrator,
  self-healing backend health, and task-executor namespaces.
- Adds explicit config component dependencies for the affected components.
- Updates task-executor runner coverage to assert missing-resource behavior
  through the shared config loader instead of a private runner helper.

## Verification

```bash
clojure -M:dev:test -e "(require '[clojure.test :as t] 'ai.miniforge.operator.core-test 'ai.miniforge.task-executor.orchestrator-test 'ai.miniforge.task-executor.runner-test 'ai.miniforge.self-healing.stream-recovery-test) (let [result (t/run-tests 'ai.miniforge.operator.core-test 'ai.miniforge.task-executor.orchestrator-test 'ai.miniforge.task-executor.runner-test 'ai.miniforge.self-healing.stream-recovery-test)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))"
```

- 93 tests, 198 assertions, 0 failures, 0 errors
- `bb review`
  - 288 violations, down from the 308 baseline after PR #1270
