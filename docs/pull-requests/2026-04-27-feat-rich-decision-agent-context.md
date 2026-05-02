<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat: emit richer supervisory decision and agent context

## Overview

Enrich the control-plane producer events and supervisory projections so
downstream clients receive actionable decision context and richer agent state
without inventing UI-only fields.

This PR stays within the producer, event-stream, adapter, and supervisory-state
layers. It does not change the workflow FSM, phase transition authority, or
decision model semantics.

## Motivation

The current supervisory clients can already render:

- `DecisionCard.decision_type`
- `DecisionCard.context`
- `DecisionCard.options`
- `DecisionCard.deadline`
- `DecisionCard.comment`
- `AgentSession.metadata`
- `AgentSession.task`

But `miniforge` was not actually emitting most of that data on the wire.

In practice that meant:

- decision queues collapsed into UUIDs plus a vague summary
- bulk approvals had no real grouping context beyond the summary line
- agent detail had almost no useful run/task metadata
- clients had to guess whether missing context was a rendering problem or a
  producer problem

This PR fixes the producer side of that gap.

## Changes In Detail

### 1. Preserve richer decision context on `:control-plane/decision-*`

`event-stream.core/cp-decision-created` now accepts and emits the richer fields
already present on control-plane decisions:

- `:cp/priority`
- `:cp/type`
- `:cp/context`
- `:cp/options`
- `:cp/deadline`

`event-stream.core/cp-decision-resolved` now also preserves:

- `:cp/comment`

`control-plane.orchestrator/submit-decision-from-agent!` now projects those
fields from the created decision record into the emitted event, and
`resolve-and-deliver!` now carries the optional resolution comment.

### 2. Stop dropping agent registration metadata

`control-plane.orchestrator/run-discovery-pass` previously emitted only the
registered agent id, vendor, and name. It now preserves the rest of the
already-known registration state:

- `:cp/external-id`
- `:cp/capabilities`
- `:cp/metadata`
- `:cp/tags`
- `:cp/heartbeat-interval-ms`

That means supervisory-state can retain richer `AgentSession` records instead of
reconstructing a thinner shadow of the registry.

### 3. Mirror heartbeat task context into supervisory state

Stable status is still meaningful when the task changes. This PR makes that
observable by:

- enriching `:control-plane/agent-heartbeat` with optional `:cp/task` and
  `:cp/metrics`
- emitting heartbeat events during poll passes even when normalized status does
  not change
- updating supervisory-state accumulation so `AgentSession.task` and
  `AgentSession.metrics` track the latest heartbeat payload

### 4. Preserve richer native miniforge agent metadata

`adapter-miniforge` now carries more source event context into
`AgentSession.metadata` when it is present on native miniforge events:

- `:workflow-spec`
- `:workflow-phase`
- `:agent-context`
- `:phase-context`
- `:message`

This keeps the producer aligned with the existing “metadata is open” contract
instead of forcing downstream clients to infer context from a single message
string.

## Scope And Non-Goals

This PR intentionally does not:

- add new workflow-machine states
- change the finite state machine refactor or phase execution semantics
- invent new client-shaped decision fields outside the existing decision model
- solve every detail-view UX gap by itself

The goal here is to make authoritative producer data richer and more faithful to
the canonical decision and registry state that already exists.

## Why This Shape

The cleanest source of truth was already in-process:

- the control-plane decision record already had type, context, options, and
  deadline
- the control-plane registry already had agent metadata, capabilities, and task
  context

The bug was that the event projection was dropping those fields before
supervisory-state and downstream clients ever saw them.

So the right fix is to preserve existing canonical data across the event
boundary, not to invent special-case TUI payloads.

## Enabled Follow-On Work

This producer slice is intended to make all supervisory clients denser and more
actionable immediately, especially `miniforge-control`.

Concrete downstream effects:

1. decision queues can render the actual decision type, context, options, and
   resolution comments already supported by the current Rust contract
2. agent detail can rely on richer metadata/task state instead of UUID-only
   placeholders
3. bulk decision resolution can group around meaningful context instead of just
   summary text

## Testing Plan

Executed in repository root:

```sh
bb test components/event-stream components/control-plane components/adapter-miniforge components/supervisory-state
```

Result:

- targeted producer/control-plane bricks passed
- no failures in the enriched event-stream, control-plane, adapter-miniforge,
  or supervisory-state coverage

## Checklist

- [x] Decision-created events preserve existing decision context fields
- [x] Decision-resolved events preserve optional operator comment
- [x] Agent registration events preserve already-known registry metadata
- [x] Heartbeat events preserve task/metrics and update supervisory sessions
- [x] Native miniforge adapter metadata is richer without changing protocol
- [x] Tests cover the richer event and projection behavior
