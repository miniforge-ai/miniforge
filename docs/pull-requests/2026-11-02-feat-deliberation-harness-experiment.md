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

The private-board ablation is a development control only. It is not the
N14 §4.4 `:visibility :none` switch, and no condition here claims N15
conformance; `turn/conditions` says so in its docstring.

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
    `board/activate-participant`. No operations is a pass; any other
    malformed shape goes to admission, which rejects it, so a bad payload
    never throws out of a cell.
  - The model view keeps a question's answer and a superseded record's
    replacement, which the snapshot's field whitelist dropped.
  - Each board is seeded with the goal and one experiment object per
    fixture observation.
  - `attach-observation` records the receipt, then admits the observation
    through `board/admit` as the `:fixture-reader` participant, which holds
    the verifier responsibility. The observation becomes execution evidence
    that supports its experiment. It is deduplicated by attested receipt,
    under the first id no object already uses, so a model can neither forge nor
    pre-empt it (#2335's provenance stages reject model-made execution
    evidence).
- `experiment`
  - `run-cell` records each turn's start before calling the model, and
    records each activation and the cell result.
  - It stops on any journal anomaly.
  - It refuses a condition outside `conditions` with an `:invalid-input`
    anomaly before any call or record, so a typo cannot fall through to a
    private-board run.
  - Scoring: only a complete, in-budget closing turn can succeed. A failure
    is a scored cell, never retried.
  - Evidence completeness counts only the observations the closing turn's
    board showed it. An inspection requested on the closing turn, or one that
    never reaches the closing participant, earns no credit.
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
- The snapshot replayed observations without validation, onto the goal,
  which is not a legal `:attach-evidence` target. It also credited every
  requested inspection, whether or not any model saw the result.

Open design question: in the `independent` condition, the closing selector
starts from a fresh board and receives only solutions and rationales, so it
never sees an observation and that condition earns no evidence credit.
Before this change it earned credit it had not seen. Delivering observations
to candidates would need a budgeted continuation turn, which changes the
condition's allowance; that is left for the operator to decide.

## Testing Plan

`experiment_test`:

- Shared and blind cells have identical allowance, schedule and closing
  turn, but only the shared board relays a peer's contribution.
- No request carries the sealed answer or runtime internals.
- An adapter anomaly yields a failed, scored cell after exactly one call.
- Observations requested only on the closing turn earn no credit.
- A model object squatting on an observation's id does not block it, and
  each observation supports its experiment.
- Model-made execution evidence with a valid-looking receipt is rejected.
- An unknown condition is refused before any call or record.
- A malformed `:operations` value is rejected and the cell completes.
- `turn_test`: the model view keeps an answer.
- An over-budget closing call cannot succeed.
- A journal failure stops the cell.
- The report states G0 is not evaluated and shows the paired delta.

Ran locally on plain Clojure 1.12 with a local malli stand-in (Clojars is
unreachable from the authoring sandbox):

- the 5 harness suites: 46 tests, 149 assertions, 0 failures. The credit,
  squatting, unknown-condition, malformed-operations and model-view tests
  fail on the previous code; the forgery test guards
  #2335's provenance stages;
- clj-kondo 2026.10.26 (the CI version) on `bases components development/src`:
  0 errors.

`poly test` and the Polylith check ran only in CI. The harness suites run in
CI through `projects/deliberation-lab` (#2344).

## Deployment Plan

None. Used by the `deliberation-lab` base in the next PR.

## Related Issues/PRs

- Builds on #2334 (harness foundations) and on #2332 (run loop, which brings
  the admission boundary).

## Checklist

- [x] No throws on data paths
- [x] Report always disclaims G0
- [x] Named constants carry intent docstrings (dewey 006)
