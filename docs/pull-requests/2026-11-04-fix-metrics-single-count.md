<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Count each phase's spend once in the workflow metrics

## Overview

Part of #2378. The workflow runner now adds a phase's `[:phase :metrics]`
to `:execution/metrics` on top of the total the phase started from. The
software-factory phases add that spend themselves in `:leave`.
The runner's merge therefore counted every plan, implement, review, verify and
release phase's tokens and cost twice.

## Motivation

`leave-plan`, `leave-implement`, `accumulate-base-ctx` (review),
`leave-verify` and `leave-release` each set `[:phase :metrics]` and then
`update-in [:execution/metrics :tokens]` / `:cost-usd` / `:duration-ms`
by the same values. After `:leave`, `execute-entered` in
`workflow/execution_step.clj` calls `process-phase-result`, whose
`record-phase-metrics` merges `[:phase :metrics]` into `:execution/metrics`
again. A probe phase that does both, run through `run-pipeline`, ends with
`{:tokens 200 :cost-usd 2.0}` for a spend of 100 tokens and $1.00. The
workflow summary's token count and cost were therefore twice the real
figure for every software-factory phase that reported spend.

## Changes in Detail

In `execution_step.clj`, `execute-entered` passes the post-`:leave` context to
`process-phase-result`. It first restores `:execution/metrics` to the value
the phase was entered with, so the runner's merge is the only addition.

Why the runner and not the phases: the fix stays in one file within the
SL003 layer budget. `plan.clj` (4 layers) and `implement.clj` (7) are over
it. It also covers any other phase that both reports and adds its spend.
Every `:leave` that writes `:execution/metrics` on main writes only
`:tokens`, `:cost-usd` and `:duration-ms`. The SF phases' writes equal
their `[:phase :metrics]`. Explore, pr-monitor and the
compliance-scanner phases add only `:duration-ms`, which
`transition-to-completed` and `transition-to-failed` overwrite with the
wall-clock duration (`stamp-wall-clock-duration`).

## Testing Plan

- New `components/workflow/test/ai/miniforge/workflow/phase_metrics_single_count_test.clj`
  runs `run-pipeline` with probe phases:
  - one whose `:leave` reports and also adds its spend, as the SF phases
    do (100 tokens, $0.25);
  - one that only reports;
  - a pipeline with one of each.
- Against main's `execution_step.clj` it fails 4 of 6 assertions:
  `200` instead of `100`, `0.5` instead of `0.25`, and `300` instead of
  `200`. With the fix it passes. The workflow and CLI tests that read
  `:execution/metrics` also pass. These include `metrics-accumulation`, `state`,
  `run7-regression`, `runner-extended`, `runner-cleanup`,
  `terminal-receipt`, `runner-done-facts`, `runner-events`,
  `context-duration`, `dag-success-next-phase`, `runner`,
  `dag-operator-control`, `plan-executor-pipeline` and `resume`
  (181 tests, 619 assertions).

## Deployment Plan

No configuration change. Workflow totals for runs with software-factory
phases drop to the real spend. A cost budget compared against
`:execution/metrics :cost-usd` mid-run now sees the real figure, not
twice it.

## Related Issues/PRs

- #2378: found while tracing token and cost reporting to the summary.
- #2383, #2388: the other #2378 PRs. They are independent of this one.
