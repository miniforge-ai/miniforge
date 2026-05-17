# Fix: Dogfood stale status

## Overview

Mark quiet running workflow checkpoints as `stale` in `miniforge status`.

## Motivation

During dogfooding of `work/event-log-tool-visibility.spec.edn`, a resumed
workflow was interrupted after entering a non-converging review/implement loop.
No `bb miniforge resume` or child agent process remained, but
`bb miniforge status <workflow-id>` still reported `running` because the
reconstructed checkpoint had not reached a terminal state.

Checkpoint-first dogfooding needs status output that distinguishes active
workflows from abandoned checkpoints.

## Changes in Detail

- Add CLI status config for the running-stale threshold.
- Classify non-terminal workflows with old last events as `stale`.
- Localize workflow status labels through the CLI message catalog.
- Cover stale and recent running checkpoint classification in CLI tests.

## Testing Plan

- `clj-kondo --lint bases/cli/src/ai/miniforge/cli/main.clj bases/cli/src/ai/miniforge/cli/app_config.clj
  bases/cli/test/ai/miniforge/cli/main_test.clj`
- `clojure -M:dev:test -e "(require 'ai.miniforge.cli.main-test 'clojure.test) (clojure.test/run-tests
  'ai.miniforge.cli.main-test)"`
- `bb miniforge status 3927baf8-c9db-44d3-b5fb-5a1552dbe554`
- `bb pre-commit`

## Deployment Plan

Merge normally. This only affects CLI status rendering and classification.

## Related Issues/PRs

- Dogfood checkpoint: `3927baf8-c9db-44d3-b5fb-5a1552dbe554`
- Spec: `work/event-log-tool-visibility.spec.edn`

## Checklist

- [x] Resume dogfood from checkpoint instead of restarting.
- [x] Confirm stale interrupted workflow is no longer reported as running.
- [x] Add focused CLI tests.
- [x] Run full pre-commit.
