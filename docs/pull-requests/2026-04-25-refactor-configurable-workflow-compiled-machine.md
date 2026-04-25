# Refactor Configurable Workflow To Compiled Machine

## Summary

- replace the configurable workflow runner's ad hoc `workflow.state` loop
  with the authoritative compiled execution machine
- derive `:workflow/pipeline` from legacy `:workflow/phases` so
  configurable workflows execute through the same FSM abstraction as
  standard workflows
- keep compatibility projections for `:execution/current-phase`,
  `:execution/phase-index`, and phase transition history at the
  configurable runner boundary
- localize new configurable workflow messages in `workflow/messages/en-US.edn`

## Blocker Fixes Folded Into This PR

- add `components/bb-dev-tools/src` and `components/bb-dev-tools/test`
  to the repo test classpath in
  [deps.edn](/tmp/mf-configurable-compiled-machine/deps.edn)
  This was required because `bb pre-commit` could not load
  `ai.miniforge.bb-dev-tools.core-test`.
- fix an early-closing brace in
  [bases/cli/resources/config/cli/messages/en-US.edn](/tmp/mf-configurable-compiled-machine/bases/cli/resources/config/cli/messages/en-US.edn)
  That bug truncated the loaded CLI catalog and caused changed-brick
  CLI tests to fail with missing message keys.

## Validation

- `clj-kondo --lint components/workflow/src/ai/miniforge/workflow/configurable.clj`
  `components/workflow/test/ai/miniforge/workflow/configurable_test.clj`
- `clojure -M:dev:test -e "(require '[clojure.test :as t]`
  `'ai.miniforge.workflow.configurable-test) ..."`
- from `projects/miniforge`:
  `clojure -Sdeps \"$(cat deps.edn)\" -M -e "(require 'clojure.test`
  `'ai.miniforge.phase-software-factory.verify`
  `'ai.miniforge.workflow.configurable-integration-test`
  `'ai.miniforge.workflow.financial-etl-test) ..."`
- `clojure -M:dev:test -e "(require '[clojure.test :as t] 'ai.miniforge.bb-dev-tools.core-test) ..."`
- `clojure -M:dev:test -e "(require '[clojure.test :as t]`
  `'ai.miniforge.cli.workflow-runner.display-output-test`
  `'ai.miniforge.cli.main-test`
  `'ai.miniforge.cli.main.commands.evidence-test`
  `'ai.miniforge.cli.main.commands.workflow-commands-test) ..."`
- `bb pre-commit`
