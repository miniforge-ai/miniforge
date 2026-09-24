<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# test(cli): correct stale docstring claim in gc_integration_test.clj

## Overview

Docs-only change. The namespace docstring of
`bases/cli/test/ai/miniforge/cli/workflow_runner/gc_integration_test.clj`
claimed that requiring `ai.miniforge.cli.workflow-runner` starts background
threads that hang a test JVM for ~30 minutes. It cited this as the reason
tests exercise `gc-hooks` directly rather than the runner namespace. That claim is stale.

## Motivation

The rule-210 split moved process-scoped singletons into
`bases/cli/src/ai/miniforge/cli/workflow_runner/control.clj`.
The meta-loop context and operator-event consumer use `defonce` atoms:
`meta-loop-ctx` and `operator-consumer-handle`. Both start as `(atom nil)`
and initialize on the first governed workflow. Requiring the runner namespace in a fresh JVM
creates no threads. Verified 2026-08-09; sibling tests in the same
directory (`runner_control_wiring_test.clj`, `preflight_test.clj`)
require `ai.miniforge.cli.workflow-runner` directly and pass.

A false explanation invites the wrong fix. Someone modernizing the test
might conclude the file is obsolete. Someone investigating an unrelated hang
might chase nonexistent load-time threads.

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

No test bodies changed. The tests remain valid pattern-level checks of gc-hooks wiring.
The Layer 0 wrappers in `workflow_runner.clj` still call the `gc-hooks` vars:
`enqueue-workflow-gc-best-effort!` and `run-gc-pass-best-effort!`.
The documented entry/finally wiring remains in `run-workflow!` and
`run-workflow-from-spec!`.

## Verification

1. `clj-kondo` / stratum-lint pre-commit hooks on commit.
2. `bases/cli` test namespace loads and the file's tests pass
   (docstring-only edit; no behavior change possible).
