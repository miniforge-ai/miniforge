# Exceptions Resource Boundary Consolidation

## Summary

Moves additional required classpath config-resource loading to
`ai.miniforge.config.interface/load-config-resource`.

## Changes

- Replaces duplicated fail-fast EDN loaders in web dashboard config,
  operator intervention, workflow supervision, PR lifecycle config, and
  workflow resume config.
- Preserves existing schema validation and nested config extraction after
  the shared resource loader succeeds.
- Adds the explicit config component dependency for workflow resume.

## Verification

```bash
clojure -M:dev:test -e "(require '[clojure.test :as t] 'ai.miniforge.web-dashboard.config 'ai.miniforge.pr-lifecycle.controller-config 'ai.miniforge.pr-lifecycle.monitor-config 'ai.miniforge.operator.intervention-test 'ai.miniforge.pr-lifecycle.fsm-test 'ai.miniforge.workflow.supervision-test 'ai.miniforge.workflow-resume.core-test) (let [result (t/run-tests 'ai.miniforge.operator.intervention-test 'ai.miniforge.pr-lifecycle.fsm-test 'ai.miniforge.workflow.supervision-test 'ai.miniforge.workflow-resume.core-test)] (shutdown-agents) (System/exit (if (pos? (+ (:fail result) (:error result))) 1 0)))"
```

- 44 tests, 165 assertions, 0 failures, 0 errors
- `bb review`
  - 278 violations, down from the 288 baseline after PR #1271
