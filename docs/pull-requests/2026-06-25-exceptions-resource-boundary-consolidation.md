<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Exceptions Resource Boundary Consolidation

## Summary

Moves additional required classpath config-resource loading to
`ai.miniforge.config.interface/load-config-resource`.

## Changes

- Replaces duplicated fail-fast EDN loaders in web dashboard config,
  operator intervention, workflow supervision, and PR lifecycle FSM /
  controller config.
- Preserves existing schema validation and nested config extraction after
  the shared resource loader succeeds.

## Verification

```bash
clojure -M:dev:test -e "(require '[clojure.test :as t] 'ai.miniforge.web-dashboard.config 'ai.miniforge.pr-lifecycle.controller-config 'ai.miniforge.operator.intervention-test 'ai.miniforge.pr-lifecycle.fsm-test 'ai.miniforge.workflow.supervision-test) (let [result (t/run-tests 'ai.miniforge.operator.intervention-test 'ai.miniforge.pr-lifecycle.fsm-test 'ai.miniforge.workflow.supervision-test)] (shutdown-agents) (System/exit (if (pos? (+ (:fail result) (:error result))) 1 0)))"
```

- Focused namespace load/tests passed
- `bb review`
  - 280 violations, down from the 288 baseline after PR #1271
