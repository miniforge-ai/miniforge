<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(event-stream,workflow): a gate decision on a legacy stream counts only once it is written

## Overview

A gated phase advances only on an acknowledged `:gate/decision` (#2167). On a
current stream the acknowledgment follows a durable journal commit. Every CLI entry
point uses a legacy stream. There, `publish!` returned the event even when the
file sink failed to write it. The file sink caught its own write error, and the
stream caught any sink error. A gate decision could therefore advance a phase
with nothing on disk. Gate decisions now publish through `publish-persisted!`,
which returns the event only when a durable sink wrote it.

## Motivation

Copilot raised this on the superseded #2043, and it was the first residual in the
production-readiness review. A recorded gate decision is the audit trail for why
a phase advanced.

## Changes in Detail

1. `sink-durability` (new): a sink can be marked durable; `not-persisted-reason`
   reads the per-sink outcomes.
2. `core/publish!` (legacy path): each sink's outcome is recorded, and the returned
   event carries the outcomes as metadata. Its return value and its isolation of
   failing sinks are unchanged.
3. `persisted-publication/publish-persisted!` (new, on the interface): a current
   stream's result passes through. On a legacy stream an anomaly or rejection
   passes through. An event passes only when the stream has a durable sink and
   every durable sink wrote it. Otherwise it returns an `:unavailable` anomaly whose
   `:publication/reason` is `:no-durable-sink` or `:sink-write-failed`.
4. `sinks.file/file-sink`: marked durable. A failed write still prints its stderr
   warning, then rethrows, so the stream logs it and records the event as not
   persisted.
5. `workflow/execution-events/emit-phase-decision!` publishes through
   `publish-persisted!`.

## Behaviour change

A gated phase on a legacy stream with no durable sink, for example
`(create-event-stream {:sinks []})`, is now refused. CLI streams use the file sink
and are unaffected. An embedder that runs gated workflows on an in-memory stream
must add a durable sink or use a current stream. Three project integration tests
did exactly that; they now use a temp-directory file sink
(`durable-stream-support`).

## Testing Plan

1. `persisted-publication-test` refuses a stream with no sinks, with only
   non-durable sinks, or with a durable sink that throws (alone or beside one that
   writes). The real file sink is accepted when it writes to a temp dir and refused
   when pointed at a regular file. Plain `publish!` still returns the event on a
   failed write.
2. `gate-decision-acknowledgment-test`: a sinkless legacy stream and a failing
   durable sink both refuse the gated phase before the DAG handoff and the
   transition. Against main's `execution_events.clj` the test has 44 failing
   assertions.
3. All event-stream test namespaces, plus the gate tests of five other bricks
   (phase, phase-deployment, phase-opsv, phase-software-factory,
   workflow-security-compliance): 621 tests, 0 failures. Project integration tests (runner, deployment config, OPSV
   lifecycle, meta agent, current terminal): 26 tests, 0 failures.
4. `~/.miniforge/events` held 1590 files before and after the runs.

## Deployment Plan

Merge after #2309, which this branch is stacked on.

## Related Issues/PRs

This builds on #2167 (acknowledged gate decisions), replaces #2043 (closed as
superseded), and is stacked on #2309 (the sinks split).

## Checklist

- [x] Tests fail without the fix
- [x] No test writes to the real events directory
- [ ] CI green, Copilot comments resolved
