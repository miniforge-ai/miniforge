<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Agent tool-call visibility — named `:agent/tool-call` events + `mf events show`

## Context

Four dogfood iterations of `work/event-log-tool-visibility.spec.edn`
have failed (Codex empty-stream, Codex+32K empty, Opus 4.6 cut off
mid-exploration, Opus+80 turns back to empty). Each iteration produced
a different shape of failure, and we couldn't diagnose them because
**the spec we were iterating on is itself the spec that would give us
the visibility to diagnose**. Per the meta-meta-loop principle:
when miniforge can't self-improve, a human steps in.

This PR ships the minimum viable subset of the spec needed to unblock
all future dogfood diagnosis — Groups 1 + 3 of the spec. Groups 2
(phase heartbeats) and 4 (rule-based alerts) follow in separate PRs.

## What changed

### Event schema

- **New event type `:agent/tool-call`** (`components/event-stream/.../core.clj`).
  Fields: `:tool/name`, `:tool/names` (vector for Claude's multi-tool
  blocks), `:tool/call-id`, `:tool/args-preview` (bounded).
- Exported from `event-stream.interface` and
  `event-stream.interface.events`.
- **Preserves `:agent/status :tool-calling`** for backward compat —
  consumers of the legacy event keep working while observers migrate.

### Stream parser enrichment

- **Codex parser** (`components/llm/.../llm_client.clj`) — `item.completed`
  with `mcp_tool_call` or `tool_call` type now emits
  `{:tool-use true :tool-name ... :tool-call-id ...}` instead of the
  previous `nil` / empty-delta. Codex's reasoning items are still
  ignored but tool names are now visible.
- **Claude parser** was already extracting `:tool-name` + `:tool-names`
  — the data was flowing through but the callback threw them away.

### Callback wiring

- `event-stream.interface.callbacks/create-streaming-callback` now
  publishes BOTH the new `:agent/tool-call` and the legacy
  `:agent/status :tool-calling` when `:tool-use` fires. Moves args-
  preview / call-id / names from the parsed event into the event
  envelope.

### CLI — `mf events show <workflow-id>`

New subcommand in `bases/cli/.../observability.clj`:

```bash
mf events show <workflow-id>             # filtered timeline (no chunks, with status)
mf events show <workflow-id> --no-status # tight timeline, phase+DAG+errors only
mf events show <workflow-id> --filter :agent/tool-call
```

Reads `~/.miniforge/events/<workflow-id>/*.json`, strips Transit-style
key prefixes, sorts chronologically, renders one event per line with
the right summary: tool name for tool calls, phase outcomes with
duration + error, DAG decisions with reason, workflow transitions.

Sample output on a recently-failed workflow:

```text
Timeline for workflow 06e4a9b7-… — 13 event(s)
────────────────────────────────────────────────────────────────
08:04:57  → explore started
08:04:57  ✓ explore success (0.0s)
08:04:57  → plan started
08:10:19  ✓ plan failure (321.3s) — :llm-content-length 0 …
08:10:19  ⇒ DAG skipped — no-plan-id
08:10:19  → implement started
08:21:26  ✓ implement failure (667.6s) — Curator: implementer wrote no files
```

Post-mortem on a dogfood failure is now seconds of reading, not hours
of grepping JSON.

## What next dogfood runs will tell us

Once this lands, the next event-log-tool-visibility re-run will emit
`:agent/tool-call` events with real tool names. We'll finally see
whether the planner is calling `Read`/`Grep`/`Glob` over and over,
whether it ever invokes the MCP `submit-artifact` tool, and what
specific files it's reading. The three ambiguous root causes for
iteration 4's empty stream (MCP path mismatch / turn-end without text
block / stream drop) become distinguishable from the log alone.

## Tests

- `components/event-stream/test/.../agent_tool_call_test.clj` — 5
  tests, 17 assertions. Covers single + multi-tool, empty-info, args-
  preview bounding, distinctness from legacy `:agent/status`.
- All pass; pre-commit suite green.

## Non-goals (follow-ups)

- **`:tool/call-completed` pair events** — requires matching tool
  invocation to its result across stream chunks, which is a bigger
  refactor. Deferred to Group 2's heartbeat spec.
- **Phase-level heartbeats** — every N seconds regardless of activity.
  Group 2 of the spec.
- **Rule-based alerts** — `:stream-stalled` when heartbeat gap >
  threshold. Group 4; depends on heartbeats first.

## References

- `work/event-log-tool-visibility.spec.edn` — parent spec
- Four prior failed dogfood iterations documented in this thread
- The meta-meta-loop principle: when miniforge can't, we do.
