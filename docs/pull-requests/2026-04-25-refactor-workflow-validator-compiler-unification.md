<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Refactor Workflow Validator And Compiler Unification

## Summary

- move legacy workflow normalization into shared
  [components/workflow/src/ai/miniforge/workflow/definition.clj](/components/workflow/src/ai/miniforge/workflow/definition.clj)
  so configurable execution and validation use the same
  `:workflow/phases -> :workflow/pipeline` projection
- make the execution-machine compiler normalize workflows directly, then
  surface localized validation messages for unknown transition targets
- replace the old hand-rolled DAG validation in
  [components/workflow/src/ai/miniforge/workflow/validator.clj](/components/workflow/src/ai/miniforge/workflow/validator.clj)
  with compiled-machine validation, and remove the extra machine-validation
  pass from
  [components/workflow/src/ai/miniforge/workflow/registry.clj](/components/workflow/src/ai/miniforge/workflow/registry.clj)

## Why

- registration, loading, and configurable execution were still using two
  different authorities for workflow validity
- legacy phase workflows could execute through the compiled machine path
  while validation still reasoned about a separate graph model
- this slice makes registration and loading validate the same machine shape
  the runtime actually executes

## Validation

- `clj-kondo --lint components/workflow/src/ai/miniforge/workflow/definition.clj`
  `components/workflow/src/ai/miniforge/workflow/configurable.clj`
  `components/workflow/src/ai/miniforge/workflow/fsm.clj`
  `components/workflow/src/ai/miniforge/workflow/validator.clj`
  `components/workflow/src/ai/miniforge/workflow/registry.clj`
  `components/workflow/test/ai/miniforge/workflow/definition_test.clj`
  `components/workflow/test/ai/miniforge/workflow/validator_test.clj`
  `components/workflow/test/ai/miniforge/workflow/registry_test.clj`
- `clojure -M:dev:test -e "(require '[clojure.test :as t]`
  `'ai.miniforge.workflow.definition-test`
  `'ai.miniforge.workflow.validator-test`
  `'ai.miniforge.workflow.registry-test`
  `'ai.miniforge.workflow.configurable-test`
  `'ai.miniforge.workflow.fsm-test) ..."`
- `bb test components/workflow`
- `bb pre-commit`
