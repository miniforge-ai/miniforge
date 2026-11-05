<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Record native Codex tool calls in the LLM response

## Overview

Part of #2378. The Codex stream parser now turns every completed native
tool item into a tool-call signal. A native Codex run's response reports
the shell commands, file changes, MCP calls, sub-agent calls and web
searches it ran, in `:tools-called` and `:tool-call-count`. It also keeps
the prompt-cache write count Codex reports on `turn.completed`.

## Motivation

`parse-codex-stream-line` in `llm_client/cli_output.clj` recognised only
`agent_message`, `mcp_tool_call` and two item types (`tool_call`,
`tool_result`) that current Codex does not emit. Codex reports its own
tools as `command_execution` and `file_change` items, which parsed to nil.
A session that wrote files and ran local checks therefore returned
`:tools-called []` and `:tool-call-count 0`, while `:num-turns 1` counted
the one `codex exec` prompt. The implementer's `:implementer/llm-called`
log showed `tools-called []` for runs that did real local work.

## Source of the event format

The event shapes come from openai/codex at commit
`c90354172c5c49535b122443e9a8ed52c2233b80`, read with `gh api` and not
run:

- `codex-rs/exec/src/exec_events.rs`: `ThreadEvent` (`thread.started`,
  `turn.started`, `item.started`, `item.updated`, `item.completed`,
  `turn.completed`, `turn.failed`, `error`), `ThreadItemDetails`
  (`agent_message`, `reasoning`, `command_execution`, `file_change`,
  `mcp_tool_call`, `collab_tool_call`, `web_search`, `todo_list`, `error`)
  and `Usage` (`input_tokens`, `cached_input_tokens`,
  `cache_write_input_tokens`, `output_tokens`, `reasoning_output_tokens`).
- `codex-rs/exec/src/event_processor_with_jsonl_output.rs`: every started
  item gets exactly one `item.completed`, including items still running
  when the turn ends (`reconcile_unfinished_started_items`). `turn.completed`
  carries the thread's total usage. `turn.failed` carries no usage.

## Changes in Detail

1. `cli_output.clj`, `parse-codex-stream-line`:
   - `item.completed` with type `command_execution`, `file_change`,
     `collab_tool_call` or `web_search` parses to
     `{:tool-use true :tool-name <item type> :tool-call-id <id>}`, the same
     signal `mcp_tool_call` already produced.
   - Calls are counted on `item.completed` only. Counting `item.started`
     too would count most calls twice, and a `file_change` has no start
     event.
   - An `mcp_tool_call` without a tool name falls back to its item type, so
     it is still counted.
   - `todo_list`, `reasoning` and `error` items stay unparsed; they are
     not tool calls.
   - `turn.completed` keeps `cache_write_input_tokens` as
     `:cache-write-input-tokens` when Codex reports a number.
2. The docstring lists the current event types and states that a turn is
   one prompt, not one tool call.

No change to `stream-with-parser`: it already counts every parsed
`:tool-name` into `:tools-called`.

## Testing Plan

New `components/llm/test/ai/miniforge/llm/codex_native_events_test.clj`,
with a transcript written from the serde shapes above:

- `native-tool-items-parse-as-tool-calls-test`: each native tool item
  parses to one named call; a failed command still counts; `item.started`
  and `todo_list` do not.
- `turn-completed-keeps-cache-write-tokens-test`.
- `codex-run-reports-native-tool-calls-test`: a streamed Codex client run
  reports four calls and one turn, and a run that ends in `turn.failed`
  still reports the three calls it made.

On main's `cli_output.clj` the new namespace fails 22 of 30 assertions.
For example, the run reports `["context_read"]` instead of four calls.
With the fix it passes, together with `ai.miniforge.llm.interface-test`
(75 tests, 407 assertions), which holds the existing Codex parser cases.

## Deployment Plan

No configuration change. Codex responses gain tool names in
`:tools-called`, and `:agent/tool-call` events for native tools.

## Related Issues/PRs

- #2378: this is the tool-telemetry half. Token and cost reporting
  through phase failure and the summary are separate PRs.
- Left out: a heartbeat on `item.started` for long-running commands, and
  tool telemetry for backends without a stream parser (Cursor, OpenCode).
