<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(workflow): move the DAG scheduling loop out of dag_orchestrator.clj

## Overview

`dag_orchestrator.clj` spans 4 stratum layers, one over the rule 210 budget, so
stratum-lint blocks any commit that stages it. This PR moves the batch-scheduling
loop into a new namespace, `dag-loop`, and the plan-shape logging into
`dag-plan`. Each of the three files now fits in 3 layers. Behavior does not
change.

## Motivation

1. The cancel fixes in #2277 and their follow-ups edit `dag_orchestrator.clj`.
   Without this split, every such commit needs the stratum-lint warn mode, which
   this repo treats as a hook bypass.
2. The file's docstring said it could not drop below 4 layers. The reason was
   that tests `with-redefs` `dag-orchestrator/execute-single-task`, and the loop
   that calls it could not move to a sibling namespace without a circular
   require. Passing the loop the var `#'execute-single-task` as an argument
   removes the cycle and keeps the redef working.

## Changes in Detail

1. New `ai.miniforge.workflow.dag-loop` holds, unchanged:
   1. layer 0: `compute-ready-tasks`, `select-non-conflicting-batch`,
      `execute-tasks-batch`, `notify-batch-start`, `notify-batch-complete`,
      `partition-results`, `emit-completed-checkpoints!`,
      `emit-failed-checkpoints!`, `register-batch-branches!`,
      `run-control-state`, `await-launch-clearance!`, `cancelled-dag-result`;
   2. layer 1: `execute-dag-loop`.
2. `execute-dag-loop` takes a fourth argument, `execute-fn`, the function that
   runs one task, and gains a docstring. Its only other edit is
   `execute-single-task` becoming `execute-fn` in the one call that launches a
   batch.
3. `dag-plan` gains `warn-potential-monolith`, `task-defs->forest-shape` and
   `log-multi-parent-detected!`, unchanged except that
   `log-multi-parent-detected!` is now public, because `dag-orchestrator` calls
   it. `dag-plan` now requires `dag-executor.interface` for
   `validate-dag-forest`.
4. `dag-orchestrator` keeps `execute-plan-as-dag` (now layer 1) and
   `maybe-parallelize-plan` (now layer 2). `execute-plan-as-dag` calls the three
   moved functions through their new namespaces and passes
   `#'execute-single-task` to `dag-loop/execute-dag-loop`. Passing the var, not
   its value, means each task reads the var's binding when it starts. The old
   in-file call read it once per batch; both reads happen inside a test's
   `with-redefs` scope, because the batch joins every task before returning.
5. `emit-batch-events!` stays in `dag-orchestrator`, now at layer 0, calling
   the two checkpoint emitters through `dag-loop`. The loop never called it;
   only tests do.
6. No new re-exports (rule 008): the two `dag_orchestrator_test.clj` files call
   `compute-ready-tasks`, `select-non-conflicting-batch` and
   `partition-results` through `dag-loop`. `execute-dag-loop` had no caller
   outside the file.
7. Seven public vars of `dag-orchestrator` are gone: `execute-dag-loop` and
   the loop helpers that moved to `dag-loop`. Nothing in this repo or its
   sibling repos referenced them through `dag-orchestrator`.
8. The ns docstring no longer carries the 4-layer caveat.

Left out: `dag-executor/branch_registry.clj`'s docstring still says
`register-batch-branches!` is in `dag-orchestrator`. That file has layer
headings but no stratum metadata. Its first edit triggers a whole-file
stratum autofix, so it belongs in a mechanical PR of its own.

## Testing Plan

1. A form-by-form comparison checked main's `dag_orchestrator.clj` and
   `dag_plan.clj` against the three new files. It parses with edamame, strips
   metadata and normalizes `dag-*` aliases. Result: 46 definitions before and
   after, 44 identical, 0 removed, 0 added. The 2 changed are `execute-dag-loop` and
   `execute-plan-as-dag`, with exactly the edits in items 2 and 4 above.
2. stratum-lint reports no findings on the three files. clj-kondo reports 0
   errors and 0 warnings.
3. The workflow test namespaces that load `dag-orchestrator`, `dag-plan`,
   `execution-dag`, `dag-task-runner`, `configurable` or `interface.pipeline`
   pass, including `dag-operator-control-test` and
   `dag-resilience-execution-test`, which `with-redefs`
   `dag-orchestrator/execute-single-task`.
4. The project-level `projects/miniforge/test/.../dag_orchestrator_test.clj`
   passes.

## Deployment Plan

None. Internal namespace move with no change to configuration, data or
behavior.

## Related Issues/PRs

1. miniforge-ai/miniforge#2277: the cancel fix that edits this file. It is
   rebuilt on top of this split.
2. miniforge-ai/miniforge#2377: the cancel issue #2277 addresses.
3. miniforge-ai/miniforge#1317 and #1485: the earlier splits of this file.

## Checklist

1. [x] Movement only, apart from the `execute-fn` argument, the visibility
   change and the test call sites listed above.
2. [x] Every resulting file is within 3 layers.
3. [x] Tests that redefine `dag-orchestrator/execute-single-task` still pass.
4. [x] PR doc added (rule 721).
