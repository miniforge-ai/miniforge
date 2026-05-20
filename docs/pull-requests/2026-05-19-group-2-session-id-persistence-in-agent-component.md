<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# GROUP 2: Session ID persistence in agent component

**PR:** [#919](https://github.com/miniforge-ai/miniforge/pull/919)
**Branch:** `mf/group-2-session-id-persistence-in-agent--41c01840`

## Summary

Extends the per-phase stream-watchdog (introduced in GROUP 1 foundation /
GROUP 1 core) with session-ID capture. Adds:

- `capture-session-id!` and `get-session-id` lifecycle ops on the watchdog.
- `agent/session-captured` event constructor in `event-stream/core` and its
  `interface` + `interface/events` re-exports.
- `AgentSessionCaptured` and `AgentStreamStalled` Malli schemas in
  `event-stream/schema`.
- Registry entries for the two new agent events in `event_type_registry`.

The watchdog stores the session ID atomically via `compare-and-set!` so
concurrent callers cannot both observe nil and emit duplicate
`:agent/session-captured` events.

## Files Changed

### `agent` component

- `components/agent/src/ai/miniforge/agent/stream_watchdog.clj` (modify) — `capture-session-id!`, `get-session-id`,
  `emit-session-captured!`
- `components/agent/src/ai/miniforge/agent/interface.clj` (modify) — re-exports
- `components/agent/src/ai/miniforge/agent/interface/watchdog.clj` (modify) — re-exports
- `components/agent/test/ai/miniforge/agent/stream_watchdog_test.clj` (modify) — capture, get, idempotency,
  atomic-compare-and-set coverage

### `event-stream` component

- `components/event-stream/src/ai/miniforge/event_stream/core.clj` (modify) — `agent-session-captured` constructor,
  `agent-stream-stalled` carried from #917
- `components/event-stream/src/ai/miniforge/event_stream/interface.clj` (modify) — re-export
- `components/event-stream/src/ai/miniforge/event_stream/interface/events.clj` (modify) — re-export
- `components/event-stream/src/ai/miniforge/event_stream/schema.clj` (modify) — `AgentSessionCaptured`,
  `AgentStreamStalled` Malli schemas
- `components/event-stream/src/ai/miniforge/event_stream/event_type_registry.clj` (modify) — registry entries
- `components/event-stream/test/ai/miniforge/event_stream/stall_events_test.clj` (modify) — schema validation, re-export
  coverage

### Config

- `resources/config/default-user-config.edn` (modify) — `:agent/stream-gap-threshold-ms`,
  `:agent/per-backend-gap-thresholds` defaults under `:self-healing`

## Test Results

- `bb pre-commit` — clean (lint, format, smoke 14ns / 289 tests / 1017 assertions, GraalVM compatibility).
- `ai.miniforge.agent.stream-watchdog-test` + `ai.miniforge.event-stream.stall-events-test` — full agent + event-stream
  coverage, including Malli schema validation for both new event types.

## Review Decision

Copilot review pass (7 inline threads) addressed in the review-pass commit
on this branch:

- NPE-on-nil-watchdog in `capture-session-id!` — guarded with `if-let` on `(and watchdog (:session-id-atom watchdog))`.
- Non-atomic check-then-act — replaced with `compare-and-set!` so concurrent callers cannot both emit
  `:agent/session-captured`.
- Interface namespace docstring — updated to enumerate the actual exported operations.
- Event-stream schemas — added `AgentStreamStalled` and `AgentSessionCaptured` Malli schemas.
- Stall-events test suite — now validates constructed events against the schemas via `m/validate`.
- Test workflow-id defaults — switched from `"wf-test"` string to `(random-uuid)` to match the `:workflow/id uuid?`
  schema constraint.
- Phase-id key — switched from `:phase/id` to `:workflow/phase` to match the event-stream component's correlation
  convention.
- PR-doc Files Changed — backfilled with the full file set.
