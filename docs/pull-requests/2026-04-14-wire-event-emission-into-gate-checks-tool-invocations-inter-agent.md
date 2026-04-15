<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Wire event emission into gate checks, tool invocations, inter-agent

**PR:** [#542](https://github.com/miniforge-ai/miniforge/pull/542)
**Branch:** `mf/wire-event-emission-into-gate-checks-too-ada76ce4`

## Summary

Wire N3-compliant event emission into inter-agent messaging and phase telemetry.
Events are published via event-stream constructors (which wrap `create-envelope`
and assign monotonic `event/sequence-number`). All emission is a safe no-op when
no event stream is present.

## Changes

- **messaging impl** -- `send-message-impl` emits `:agent/message-sent` and
  `:agent/message-received` via `emit-inter-agent-event!`. No fallback paths.
- **messaging interface** -- Thin pass-throughs only; event emission lives in
  the impl layer to avoid double-firing.
- **phase telemetry** -- `emit-phase-started!`, `emit-phase-completed!`,
  `emit-agent-started!`, `emit-agent-completed!`, and all milestone functions
  resolve constructors via `requiring-resolve` and return nil on failure.
  No manually-constructed fallback events.
- **phase interface** -- Removed legacy `emit-milestone-reached!` re-export.
  Use `emit-milestone-completed!` instead.
- **event-stream** -- Added inter-agent, gate, tool, and milestone event
  constructors to the public interface.

## Test Plan

- `emit-inter-agent-event!` publishes to a real event stream
- Telemetry milestone functions publish events via the stream
- Nil stream is a safe no-op (returns nil, no exceptions)

## Files Changed

- `components/agent/src/ai/miniforge/agent/interface/messaging.clj` (modify)
- `components/agent/src/ai/miniforge/agent/protocols/impl/messaging.clj` (modify)
- `components/agent/src/ai/miniforge/agent/protocols/records/messaging.clj` (modify)
- `components/event-stream/src/ai/miniforge/event_stream/core.clj` (modify)
- `components/event-stream/src/ai/miniforge/event_stream/interface.clj` (modify)
- `components/event-stream/src/ai/miniforge/event_stream/interface/events.clj` (modify)
- `components/phase/src/ai/miniforge/phase/interface.clj` (modify)
- `components/phase/src/ai/miniforge/phase/telemetry.clj` (modify)

## Test Results

_No test artifacts available._

## Review Decision

_No review artifacts available._
