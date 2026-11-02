<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(deliberation-harness): development comparison cells and report

## Overview

Second harness PR for the deliberation development lab (snapshot `a345990`).
It adds the cell runner, which takes one task, condition, allowance tier and
replicate to completion under a fixed schedule, and the Markdown report. Each
turn goes through the deliberation workspace's trusted admission boundary.

## Motivation

N15 §0.5 allows development comparisons and requires every report to state
that G0 is not evaluated. A cell compares the shared typed workspace against
four development conditions. The ablated condition keeps the same schedule
and budget but gives each participant a private board.

## Changes in Detail

- `turn`
  - The schedule (`actor`), with the closing turn always the synthesizer's.
  - Private boards for every condition except `:workspace`.
  - The public request: the task's public view, the board's model view
    without runtime internals, the participant's feedback and the
    condition's handoff.
  - `respond`: never sends a request over `:max-input-bytes`, and never
    retries an adapter anomaly.
  - `commit-turn` admits the payload through
    `board/activate-participant`.
  - `attach-observation` records receipt-backed execution evidence as a
    trusted runtime capability.
- `experiment`
  - `run-cell` records each turn's start before calling the model, and
    records each activation and the cell result.
  - It stops on any journal anomaly.
  - Scoring: only a complete, in-budget closing turn can succeed. A failure
    is a scored cell, never retried.
- `report`: the success table, normalized spend, unsettled-usage counts and
  paired shared-minus-blind deltas. The text comes from an en-US catalog and
  always says "G0: not evaluated".
- `interface`: `conditions`, `initial-workspace`, `run-cell`,
  `render-report`.

Deviations from the snapshot:

- The adapter returns anomalies instead of throwing.
- The turn logic is split into named steps (dewey 210).
- Report prose is localized (dewey 050).
- The model view projects for the acting participant rather than a fixed
  `:synthesizer`. Under `:full` visibility of its own board, the result is
  the same.

## Testing Plan

`experiment_test`:

- Shared and blind cells have identical allowance, schedule and closing
  turn, but only the shared board relays a peer's contribution.
- No request carries the sealed answer or runtime internals.
- An adapter anomaly yields a failed, scored cell after exactly one call.
- An over-budget closing call cannot succeed.
- A journal failure stops the cell.
- The report states G0 is not evaluated and shows the paired delta.

Ran locally on plain Clojure 1.12 with a local malli stand-in (Clojars is
unreachable from the authoring sandbox): the 4 harness suites, 13 tests,
69 assertions, 0 failures. `poly test`, clj-kondo and the Polylith check ran
only in CI.

## Deployment Plan

None. Used by the `deliberation-lab` base in the next PR.

## Related Issues/PRs

- Builds on #2334 (harness foundations) and on #2332 (run loop, which brings
  the admission boundary).

## Checklist

- [x] No throws on data paths
- [x] Report always disclaims G0
