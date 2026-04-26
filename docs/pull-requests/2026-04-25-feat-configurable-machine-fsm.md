# feat/configurable-machine-final

## Summary

Moves configurable workflow execution onto the authoritative compiled execution
machine.

Legacy `:workflow/phases` definitions are now normalized into canonical
`:workflow/pipeline` data before registration, validation, and execution. The
configurable runner now uses `workflow.context` and machine transitions instead
of `workflow.state` to advance phases.

## Key Changes

- added workflow-definition normalization in
  [definition.clj](/private/tmp/mf-configurable-machine-final/components/workflow/src/ai/miniforge/workflow/definition.clj)
- migrated configurable execution in
  [configurable.clj](/private/tmp/mf-configurable-machine-final/components/workflow/src/ai/miniforge/workflow/configurable.clj)
  to machine-authoritative state transitions
- fixed configurable task-type inference in
  [agent_factory.clj](/private/tmp/mf-configurable-machine-final/components/workflow/src/ai/miniforge/workflow/agent_factory.clj)
  so non-task-type phase ids map through agent role
- made validator and registration normalize legacy phase workflows before
  compiled-machine reachability checks in
  [validator.clj](/private/tmp/mf-configurable-machine-final/components/workflow/src/ai/miniforge/workflow/validator.clj)
  and
  [registry.clj](/private/tmp/mf-configurable-machine-final/components/workflow/src/ai/miniforge/workflow/registry.clj)

## Validation

- `clj-kondo --lint components/workflow/src/ai/miniforge/workflow/configurable.clj
  components/workflow/src/ai/miniforge/workflow/definition.clj
  components/workflow/src/ai/miniforge/workflow/validator.clj components/workflow/src/ai/miniforge/workflow/registry.clj
  components/workflow/src/ai/miniforge/workflow/fsm.clj components/workflow/src/ai/miniforge/workflow/agent_factory.clj
  components/workflow/test/ai/miniforge/workflow/configurable_test.clj
  components/workflow/test/ai/miniforge/workflow/definition_test.clj
  components/workflow/test/ai/miniforge/workflow/agent_factory_test.clj
  projects/miniforge/test/ai/miniforge/workflow/configurable_integration_test.clj`
- `clojure -M:dev:test -e "(require 'ai.miniforge.workflow.configurable-test 'ai.miniforge.workflow.validator-test
  'ai.miniforge.workflow.registry-test 'ai.miniforge.workflow.agent-factory-test
  'ai.miniforge.workflow.definition-test)(let [result (clojure.test/run-tests 'ai.miniforge.workflow.configurable-test
  'ai.miniforge.workflow.validator-test 'ai.miniforge.workflow.registry-test 'ai.miniforge.workflow.agent-factory-test
  'ai.miniforge.workflow.definition-test)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))"`
- `bb pre-commit`
