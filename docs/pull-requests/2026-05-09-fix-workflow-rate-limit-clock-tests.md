<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(workflow): remove wall-clock waits from rate-limit tests

## Overview

Make the workflow DAG resilience tests deterministic by routing
rate-limit time calculations through the shared clock component and
injecting short waits in tests instead of sleeping against the real
wall clock.

The immediate trigger was `dag_resilience_execution_test` hanging the
changed-bricks hook near local `2pm`. The test used a literal
`"You've hit your limit · resets 2pm"` message, and
`wait-for-reset!` computed the real wait from `System/currentTimeMillis`
and then called `Thread/sleep`. When the suite happened to run shortly
before `2pm`, the test exercised the short-wait path and stalled the
whole hook.

## Root cause

`components/workflow/src/ai/miniforge/workflow/dag_resilience.clj`
mixed two hard-coded wall-clock dependencies into rate-limit handling:

- `parse-reset-instant` used `Instant/now` and `ZonedDateTime/now`
- `millis-until-reset` used `System/currentTimeMillis`
- `wait-for-reset!` called `Thread/sleep` directly

That made the tests time-sensitive in two ways:

- absolute reset strings like `"resets 2pm"` changed behaviour based
  on when the suite happened to run
- relative reset strings like `"resets in 1 seconds"` always incurred
  a real sleep

## Fix

- Add `ai.miniforge/clock` as an explicit workflow dependency.
- Route reset-time parsing and wait-duration math through
  `ai.miniforge.clock.interface/now-ms`.
- Add a dynamic `*sleep!*` seam in `dag_resilience.clj` so tests can
  advance a fake clock instead of sleeping.
- Rewrite the resilience tests to use fixed instants and explicit
  timezone expectations.
- Rewrite the DAG execution pause test to pin the clock to a known
  instant and assert that the pause path does **not** invoke the short
  sleep branch.

I did not add `tick` or `tea-time` here because the repo already has a
first-party clock abstraction with explicit test guidance. Using that
existing seam keeps the production code smaller and avoids introducing
another time-control abstraction just for one workflow component.

## Files changed

- `components/workflow/deps.edn`
- `components/workflow/src/ai/miniforge/workflow/dag_resilience.clj`
- `components/workflow/test/ai/miniforge/workflow/dag_resilience_failover_test.clj`
- `components/workflow/test/ai/miniforge/workflow/dag_resilience_execution_test.clj`

## Verification

- `clj-kondo --lint` on the touched workflow files: clean
- focused workflow resilience tests:
  - `ai.miniforge.workflow.dag-resilience-failover-test`
  - `ai.miniforge.workflow.dag-resilience-execution-test`
- `bb test` changed-bricks sweep: green
  - `5525` tests
  - `24493` assertions
  - `0` failures
  - `0` errors

## Test plan

- [x] Lint touched workflow files
- [x] Run focused DAG resilience tests
- [x] Run changed-bricks proof path (`bb test`)
