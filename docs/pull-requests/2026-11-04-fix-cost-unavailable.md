<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Mark unpriced spend in workflow metrics and show its cost as unavailable

## Overview

Part of #2378. When a phase spends tokens with no known cost, the
workflow's `:execution/metrics` now carries `:cost-unavailable? true`.
The flag survives DAG roll-ups. The CLI summary then prints
`Cost: unavailable`, or `Cost: at least $X` when part of the run was
priced. It no longer presents a summed `$0.0000` as a measured cost.

## Motivation

`:execution/metrics` holds only a summed `:cost-usd`, starting at `0.0`.
A phase whose agent reported tokens but no cost added nothing, so an
unpriced run printed `Tokens: 24885 | Cost: $0.0000`. The summary
formatter (`display_summary_lines.clj` `metrics-lines`, and the same code
in `display_print.clj` `print-workflow-summary`) also defaulted a missing
cost to `0.0`. Nothing in the metrics said the zero was not a
measurement.

## Changes in Detail

1. `workflow/context.clj`, `merge-metrics`: this merge runs for every
   phase result (`record-phase-metrics`) and for DAG metrics in
   `configurable.clj`. It sets `:cost-unavailable? true` when the phase
   metrics have positive `:tokens` and no `:cost-usd`, or already carry
   the flag. The flag stays set once set.
2. DAG roll-up: `dag_finalize.clj` `aggregate-results` sets the flag
   when any task's metrics carry it. `dag_plan.clj`
   `dag-execution-result` passes it into the DAG `:metrics`.
   `execution_dag.clj` `roll-dag-metrics-into-execution` copies it into
   the parent's `:execution/metrics`.
3. CLI summary: `metrics-lines` renders the cost through three new
   messages: `:workflow-runner/cost` (`$X`), `:workflow-runner/cost-partial`
   (`at least $X (some usage has no price)`) and
   `:workflow-runner/cost-unavailable`. `:workflow-runner/metrics` now
   takes the rendered cost. `print-workflow-summary` prints
   `metrics-lines` instead of formatting the cost again.

## What this does and does not change on its own

- The planner returns no `:cost-usd` at all, so a run with a plan
  phase that spent tokens now reads `at least $X`. That is accurate: the
  plan's cost was never in the total.
- A native Codex implement phase still reports a cost today.
  The LLM client estimates `0.0` for an unpriced model.
  The implement phase falls back to a flat per-token rate. A follow-up PR removes both
  sources. That phase's metrics will then carry no cost, and this flag applies.

## Testing Plan

- New `components/workflow/test/ai/miniforge/workflow/cost_unavailable_metrics_test.clj`:
  - `merge-metrics` marking: set on unpriced tokens, kept after a later
    priced phase, carried from a rolled-up run, and absent for priced or
    zero-token phases.
  - The DAG roll-up through `aggregate-results`, `dag-execution-result`
    and `roll-dag-metrics-into-execution`.
  - A `run-pipeline` run whose phase reports tokens without a cost.
- New `bases/cli/test/ai/miniforge/cli/workflow_runner/display_cost_test.clj`:
  the unavailable, partial and measured cost lines, and
  `print-workflow-summary`.
- These pass together with `display-output-test`, `display-test`,
  `dag-orchestrator-test` and `runner-extended-test` (152 tests,
  384 assertions). Against main's sources the new tests fail.

## Deployment Plan

No configuration change. `:execution/metrics` may gain
`:cost-unavailable? true`. Readers that ignore the key are unaffected.

## Related Issues/PRs

- #2383: native Codex tool calls (tool half of #2378).
- The implement phase keeping the implementer's tokens through curator
  fallback, and the removal of the `0.0` and flat-rate cost sources, are
  separate PRs for #2378.
