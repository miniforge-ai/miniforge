<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(deliberation-workspace): success needs current decision coverage

## Overview

A run now closes as `:success` only when every goal is terminal **and** a
currently adopted decision covers each goal, as N14 §7.1 requires.
Previously coverage was checked only when `close-goal` was admitted.

## Motivation

N14 §7.1 defines success as "all goals accepted with decision coverage". On
main, coverage was checked only when `close-goal` was admitted, so this
sequence closed as `:success`:

1. The synthesizer accepts a covering decision.
2. It closes the goal.
3. It supersedes that decision with an open replacement.

At that point no adopted decision covers the goal.

Copilot found this while reviewing #2332. The fix needed about 40 lines,
which that PR (at 599 of 600) could not hold, so it was deferred to this PR,
as recorded on that PR's review thread and in a PR comment.

## Changes in Detail

- `object`: new public `covers-goal?`, moved from `semantics` unchanged. A
  decision covers a goal when it is `:accepted` and `:depends-on` the goal.
  Admission and termination now share this one definition.
- `semantics`: `uncovered-goal?` calls `object/covers-goal?`.
- `termination`: new `goals-covered?`. `closing-rule` reaches `:success` only
  when every goal is terminal and covered on the current graph. The
  completed-with-rejected-goals rule is unchanged. When coverage is missing,
  the run continues to its next applicable rule (budget, quiescence or
  deadlock).

## Testing Plan

- `termination_test/success-closes-before-budget-exhaustion` now includes a
  covering decision. A new case supersedes that decision and expects
  `:budget-boundary` rather than `:success`; it fails on the old rule.
- `run_test/a-run-closes-as-success-when-every-goal-is-terminal` now includes
  a covering decision.

Ran locally on plain Clojure 1.12 with a malli stand-in (Clojars is
unreachable from the authoring sandbox): all 12 deliberation-workspace suites,
192 tests, 590 assertions, 0 failures. clj-kondo 2026.10.26 (the CI version) on
`bases components development/src`: 0 errors. CI runs these suites through
`projects/deliberation-lab` (#2344).

## Deployment Plan

None.

## Related Issues/PRs

- Deferred from #2332 (run loop, scheduler and termination).
- Contract: N14 §2.3 and §7.1 (#2328).

## Checklist

- [x] Regression test for the reported sequence
- [x] One shared definition of coverage
- [x] Copyright header on the PR doc
