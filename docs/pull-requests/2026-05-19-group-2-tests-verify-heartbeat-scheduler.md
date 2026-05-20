<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# GROUP 2 tests — Verify heartbeat scheduler

**PR:** [#932](https://github.com/miniforge-ai/miniforge/pull/932)
**Branch:** `mf/group-2-tests-verify-heartbeat-scheduler-c345a9dd`

## Summary

GROUP 2 tests — Verify heartbeat scheduler.

## Files Changed

- `components/event-stream/src/ai/miniforge/event_stream/heartbeat.clj` (modify) — daemon thread factory, interval-ms
  validation, ScheduledFuture retention, IDeref guard
- `components/event-stream/test/ai/miniforge/event_stream/heartbeat_test.clj` (modify) — leak/lifecycle pins
- `components/workflow/src/ai/miniforge/workflow/runner.clj` (modify) — heartbeat lifecycle wiring, exception-safe
  on-phase-start callback wrap
- `components/workflow/src/ai/miniforge/workflow/runner_events.clj` (modify) — `start-phase-heartbeat!` /
  `stop-phase-heartbeat!`, gated on `durable-event-stream?`
- `components/workflow/test/ai/miniforge/workflow/runner_events_test.clj` (modify) — start/stop pins (nil, WebSocket,
  durable, idempotent stop)

## Test Results

_No test artifacts available._

## Review Decision

_No review artifacts available._
