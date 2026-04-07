# CLI Config And Messages Catalog Split

## Layer

Infrastructure

## Depends on

- #307 (`codex/cli-base-split`) — merged

## What this adds

- moves shared CLI app-profile defaults fully into resource-backed EDN instead of keeping fallback profile data in code
- adds a small shared resource-config loader for classpath-composed EDN config
- adds a lightweight CLI message catalog with locale fallback and placeholder substitution
- moves the shared CLI help, status, monitoring, workflow-runner, and classified-error copy behind resource-backed
  message keys
- keeps English as the only shipped locale for now, while making future locale overlays an app/config concern rather
  than a code rewrite

## Strata affected

- `bases/cli` — resource-backed app config and message lookup seam
- CLI resources — `config/cli/app.edn` and `config/cli/messages/en.edn`
- CLI tests — direct seam coverage for resource-backed app config, message lookup/rendering, and the migrated
  display/help/monitoring paths

## Why

- `app_config.clj` still carried configuration as code after the previous CLI identity split
- shared user-facing CLI strings were still hardcoded English literals scattered through runtime/display code
- this is the right point to stop the bleed: keep English for now, but make copy a resource concern before more product
  and engine naming diverge
- this creates a small i18n-ready seam without taking on full localization infrastructure yet

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.cli.app-config-test 'ai.miniforge.cli.messages-test
  'ai.miniforge.cli.main-test 'ai.miniforge.cli.main.display-test
  'ai.miniforge.cli.main.commands.monitoring-test 'ai.miniforge.cli.workflow-runner.display-test)
  (clojure.test/run-tests 'ai.miniforge.cli.app-config-test 'ai.miniforge.cli.messages-test
  'ai.miniforge.cli.main-test 'ai.miniforge.cli.main.display-test
  'ai.miniforge.cli.main.commands.monitoring-test 'ai.miniforge.cli.workflow-runner.display-test)"`
- `clojure -M -e "(require 'ai.miniforge.workflow.kernel-loader-integration-test)
  (clojure.test/run-tests 'ai.miniforge.workflow.kernel-loader-integration-test)"` from `projects/workflow-kernel`
- `bb test`
- `bb test:integration`
- `bb build:kernel`
- `bb build:cli`
- `bb build:tui`
- `bb pre-commit`
