<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# GROUP 4: Wire richer terminal events into phase-software-factory

**PR:** [#921](https://github.com/miniforge-ai/miniforge/pull/921)
**Branch:** `mf/group-4-wire-richer-terminal-events-into-292fc25f`

## Summary

Top of the stream-stall / self-healing stack. Lands the
`phase-terminal` namespace that derives the
`:phase/termination-reason` keyword for `:workflow/phase-completed`
events, and wires every phase (`plan`, `implement`, `verify`, `review`,
`release`) of the `phase-software-factory` component to attach a
termination reason to their phase-completed envelope.

Termination-reason priority order:

1. Watchdog stall → `:agent-stalled` (+ `:stall/gap-duration-ms` when known)
2. Curator / release rejection → `:curator-rejected`
3. Rate-limit / tool error → `:tool-error`
4. Default → `:normal`

Stacked on #917 / #918 / #919 / #920.

## Files Changed

### Phase-software-factory (new termination wiring)

- `components/phase-software-factory/src/ai/miniforge/phase_software_factory/phase_terminal.clj` (create)
- `components/phase-software-factory/test/ai/miniforge/phase_software_factory/phase_terminal_test.clj` (create)
- `components/phase-software-factory/src/ai/miniforge/phase_software_factory/plan.clj` (modify)
- `components/phase-software-factory/src/ai/miniforge/phase_software_factory/implement.clj` (modify)
- `components/phase-software-factory/src/ai/miniforge/phase_software_factory/verify.clj` (modify)
- `components/phase-software-factory/src/ai/miniforge/phase_software_factory/review.clj` (modify)
- `components/phase-software-factory/src/ai/miniforge/phase_software_factory/release.clj` (modify)

### Event-stream (carried from #917 / #919)

- `components/event-stream/src/ai/miniforge/event_stream/core.clj` (modify)
- `components/event-stream/src/ai/miniforge/event_stream/interface.clj` (modify)
- `components/event-stream/src/ai/miniforge/event_stream/interface/events.clj` (modify)
- `components/event-stream/src/ai/miniforge/event_stream/schema.clj` (modify) — `AgentStreamStalled`,
  `AgentSessionCaptured`
- `components/event-stream/src/ai/miniforge/event_stream/event_type_registry.clj` (modify)
- `components/event-stream/test/ai/miniforge/event_stream/stall_events_test.clj` (modify)

### Agent (carried from #918 / #919)

- `components/agent/src/ai/miniforge/agent/stream_watchdog.clj` (modify)
- `components/agent/src/ai/miniforge/agent/interface.clj` (modify)
- `components/agent/src/ai/miniforge/agent/interface/watchdog.clj` (modify)
- `components/agent/test/ai/miniforge/agent/stream_watchdog_test.clj` (modify)

### Self-healing (carried from #920)

- `components/self-healing/src/ai/miniforge/self_healing/stream_recovery.clj` (create)
- `components/self-healing/src/ai/miniforge/self_healing/backend_health.clj` (modify)
- `components/self-healing/src/ai/miniforge/self_healing/interface.clj` (modify)
- `components/self-healing/test/ai/miniforge/self_healing/stream_recovery_test.clj` (create)

### Config

- `resources/config/default-user-config.edn` (modify) — stream-gap thresholds under `:self-healing`

## Test Results

- `bb pre-commit` — clean (lint, format, smoke 14ns / 289 tests / 1017 assertions, GraalVM compatibility).
- `phase-terminal-test` + `stream-watchdog-test` + `stall-events-test` + `stream-recovery-test` — full stack pass,
  including Malli schema validation.

## Review Decision

Copilot review pass (3 inline threads) addressed in the review-pass
commit on this branch:

- `select-best-backend` empty allowed-set → returns nil instead of expanding to full fallback-order (carry-forward from
  #920).
- Default-config comment math: `{:codex 120000}` is +30 s; example bumped to `{:codex 210000}` for the actual +2 min
  (carry-forward from #917).
- PR-doc Files Changed backfilled with the full stack.

Plus carry-forward of all base-stack fixes from #917/#918/#919/#920
(`:workflow/phase` correlation key, AgentStreamStalled +
AgentSessionCaptured Malli schemas, watchdog log shape,
`capture-session-id!` atomicity, `binary-for` validation,
`evaluate-stall-recovery` no-resume-with-nil-session-id,
event_type_registry entries, interface docstring, test UUIDs).
