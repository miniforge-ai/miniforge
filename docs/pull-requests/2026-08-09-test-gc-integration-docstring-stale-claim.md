# test(cli): correct stale docstring claim in gc_integration_test.clj

## Overview

Docs-only change. The namespace docstring of
`bases/cli/test/ai/miniforge/cli/workflow_runner/gc_integration_test.clj`
claimed that requiring `ai.miniforge.cli.workflow-runner` starts JVM
background threads at namespace-load time that hang a test JVM for
~30 minutes, and that this is why the tests exercise `gc-hooks` directly
instead of the runner namespace. That claim is stale.

## Motivation

After the rule-210 namespace split of `workflow_runner.clj`, the
process-scoped singletons (meta-loop context, operator-event consumer)
live in `bases/cli/src/ai/miniforge/cli/workflow_runner/control.clj` as
lazily initialized `defonce` atoms (`meta-loop-ctx`,
`operator-consumer-handle`, both `(atom nil)` initialized on first
governed workflow start). Requiring the runner namespace in a fresh JVM
creates no threads. Verified 2026-08-09; sibling tests in the same
directory (`runner_control_wiring_test.clj`, `preflight_test.clj`)
require `ai.miniforge.cli.workflow-runner` directly and pass.

A docstring that gives a false reason for a test's design invites the
wrong fix later — someone "modernizing" the test to load the runner
would conclude the whole file is obsolete, or someone hitting an
unrelated hang would chase a load-time-thread ghost.

## Changes in Detail

One section of the namespace docstring rewritten. The old section
("Why workflow_runner.clj is not loaded here") asserted namespace load
was unsafe. The new section ("Why these tests exercise gc-hooks rather
than workflow_runner.clj") states:

1. The tests are deliberately pattern-level: they verify the lifecycle
   wiring shape via `gc-hooks` with mock collaborators.
2. Loading `workflow_runner.clj` is not the obstacle — the singletons
   moved to `control.clj` and are lazy; sibling tests require the
   runner namespace directly.
3. The earlier hang claim predates the split and no longer applies.

No test bodies changed. The tests remain valid as pattern-level tests
of the gc-hooks wiring: the `defn-` wrappers in `workflow_runner.clj`
(`enqueue-workflow-gc-best-effort!`, `run-gc-pass-best-effort!` at
Layer 0) still call through the `gc-hooks` vars, and the entry/finally
wiring points the docstring describes still exist in
`run-workflow!` and `run-workflow-from-spec!`.

## Verification

1. `clj-kondo` / stratum-lint pre-commit hooks on commit.
2. `bases/cli` test namespace loads and the file's tests pass
   (docstring-only edit; no behavior change possible).
