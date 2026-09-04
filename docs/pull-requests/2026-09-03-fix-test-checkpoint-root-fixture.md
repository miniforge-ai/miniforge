# fix(test): keep workflow tests out of the live checkpoint root

## Overview

Workflow tests that run a pipeline with default options were writing
their machine snapshots, manifests and phase checkpoints into the
developer's real `~/.miniforge/checkpoints`. This PR adds one shared
fixture that points the default checkpoint root at a fresh temp
directory for the duration of a test and deletes it afterwards, and
applies it to every test namespace that runs a pipeline unstubbed.

Depends on `refactor/stratum-headings-workflow-runner-tests`, which
lands the pre-commit stratum autofix's regroup of the same files so this
diff stays readable.

## Motivation

`runner/run-pipeline` and `runner/execute-single-iteration` persist
execution state on every iteration. The root resolves through
`checkpoint-store-paths/resolve-checkpoint-root`: an explicit
`:checkpoint/root` in opts, else `[:workflow :checkpoint-root]` from
merged config. A test that runs a pipeline with `{}` therefore lands in
the same directory a real run uses, and the one that bench forensics
(`eval/codex-traps`, `codex-gap` peg telemetry) read from.

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
`brick:gate` observation coincided with other sessions in sibling
worktrees running `bb pre-commit` and `bb test:integration`, which write
to the same live root; a live-root count is not attributable to one run.

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
config. Nothing above it works from inside a test. `MINIFORGE_HOME` is
process environment a running JVM cannot change, and it would not move
the root anyway: `config/default-user-config-fallback.edn` sets
`[:workflow :checkpoint-root]` to `~/.miniforge/checkpoints`, so the
merged config carries that value even under an empty home, and the
home-derived fallback in `default-checkpoint-root` never runs. That is
a separate defect, flagged for its own PR.

### New: `projects/miniforge/test/.../checkpoint_root_support.clj`

Project-level twin with an identical body. `bb test:integration` runs
project tests with the project's own `deps.edn` as the classpath
(project paths plus brick `src`), so a brick's `test` directory is not
loadable from there.

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
