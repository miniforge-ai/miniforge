<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(test): keep workflow tests out of the live checkpoint root

## Overview

Workflow tests that run a pipeline with default options were writing
their machine snapshots, manifests and phase checkpoints into the
developer's real `~/.miniforge/checkpoints`. This PR adds a shared fixture
that redirects checkpoints to a fresh temp directory during each test and
deletes it afterwards. It covers every test namespace running an unstubbed pipeline.

Depends on `refactor/stratum-headings-workflow-runner-tests`, which
lands the pre-commit stratum autofix's regroup of the same files so this
diff stays readable.

## Motivation

`runner/run-pipeline` and `runner/execute-single-iteration` persist
execution state on every iteration. The root resolves through
`checkpoint-store-paths/resolve-checkpoint-root`: an explicit
`:checkpoint/root` in opts, else `[:workflow :checkpoint-root]` from
merged config. A pipeline test using `{}` therefore writes to the real run
directory. Bench forensics also reads it (`eval/codex-traps` and `codex-gap`
peg telemetry).

On 2026-09-03 the live root on the reporting machine held ~187k run
directories totalling 7.3 GB, the oldest from 2026-04-23. A 600-run
sample of the ones written that day, by manifest `:workflow/workflow-id`:

| id | count | source |
|---|---|---|
| `:test` | 461 | `runner_test`, `runner_extended_test`, `runner_iteration_test`, `run7_regression_test`, `anomaly/build_initial_context_test`, project `runner_integration_test` |
| `:env-promotion-test` | 60 | `environment_promotion_integration_test` |
| nil | 41 | pipelines run on a workflow map without `:workflow/id` |
| `:test-multi-phase` | 21 | `runner_test` |
| `:dag-task-11111111-…` | 4 | not reproducible from this branch |
| `:canonical-sdlc` | 1 | not reproducible from this branch |

The gate brick writes nothing (`clojure -M:poly test brick:gate`, 54
namespaces, 0 directories against a canary root). The reported
`brick:gate` observation coincided with sibling sessions running `bb pre-commit`
and `bb test:integration` against the same live root. That count cannot be
attributed to one run.

## Changes in Detail

### New: `components/workflow/test/.../checkpoint_test_support.clj`

1. `call-with-temp-checkpoint-root` calls `f` with the path of a fresh
   `Files/createTempDirectory` root. While `f` runs,
   `checkpoint-store-paths/default-checkpoint-root` returns that path,
   so a pipeline run inside checkpoints there whether or not it passes
   `:checkpoint/root`. The directory is deleted in `finally`.
2. `with-temp-checkpoint-root` is the clojure.test fixture form for
   `use-fixtures`.

The override is a `with-redefs` on the one resolution function, below
config. Nothing above it works from inside a test. A running JVM cannot
change the `MINIFORGE_HOME` process environment. That variable would not move
the root anyway: `config/default-user-config-fallback.edn` sets
`[:workflow :checkpoint-root]` to `~/.miniforge/checkpoints`.
Merged config retains that value under an empty home, so the home-derived
`default-checkpoint-root` fallback never runs. That separate defect needs its own PR.

### New: `projects/miniforge/test/.../checkpoint_root_support.clj`

Project-level twin with an identical body. `bb test:integration` uses the
project's `deps.edn`: project paths plus brick `src` directories.
Brick `test` directories are therefore unavailable.

### Fixture applied

Workflow brick: `runner_test`, `runner_extended_test`,
`runner_iteration_test`, `run7_regression_test`,
`environment_promotion_integration_test`,
`anomaly/build_initial_context_test`.

Project `miniforge`: `runner_integration_test`.

### Duplicate helpers removed

Three private copies of the same temp-root helper are replaced by the
shared one: `runner_test`, `checkpoint_store_test`, and the project's
`opsv_lifecycle_support`. The inline phase-loader fixture that
`run7_regression_test` and `environment_promotion_integration_test`
each re-implemented is replaced by
`phase-test-support/with-workflow-phase-test-support`.

## Testing Plan

Canary: `MINIFORGE_HOME=<c>` with `<c>/config.edn` set to
`{:workflow {:checkpoint-root "<c>/checkpoints"}}`, then count
`<c>/checkpoints` after each run. A `MINIFORGE_HOME`-only canary shows
zero for the reason above and proves nothing.

| run | namespaces / tests | dirs before | dirs after |
|---|---|---|---|
| `clojure -M:poly test brick:gate` | 54 ns | 0 | 0 |
| `clojure -M:poly test brick:workflow` | 219 ns, exit 0 | (live root, see table above) | 0 |
| project `runner-integration-test`, `dag-orchestrator-test`, `opsv-lifecycle-integration-test` | 59 tests, 175 assertions | 3 | 0 |

No temp roots left behind under the JVM temp dir after any run.

## Deployment Plan

Test-only change. No runtime behaviour touched.

## Related Issues/PRs

1. Depends on `refactor/stratum-headings-workflow-runner-tests`.
2. Follow-up: make `MINIFORGE_HOME` relocate the checkpoint root
   (default resource hardcodes the home path).
3. The live root is not cleaned up by this PR. The ~187k directories
   are the operator's data to remove; the ids in the table above
   identify the test-generated ones.

## Checklist

- [x] Shared fixture, three strata, stratum-lint clean
- [x] Workflow brick namespaces covered, canary 0
- [x] Project-level namespaces covered, canary 3 → 0
- [x] Gate brick measured, canary 0
