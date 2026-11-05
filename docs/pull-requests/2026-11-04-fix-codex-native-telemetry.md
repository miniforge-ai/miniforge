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
   - A tool signal also carries `:tool-completed true`, because Codex
     reports the call only once it has finished, and `:tool-error?` from
     the item's `status`: true for `failed` or `declined`, false for
     `completed`, absent otherwise.
2. The docstring lists the current event types and states that a turn is
   one prompt, not one tool call.
3. `event-stream/interface/callbacks.clj`, `create-streaming-callback`:
   for a `:tool-completed` call it publishes `:tool/call-completed`
   right after `:agent/tool-call-started`, with the same call id, the
   outcome when known, and no duration. It records no start time for
   such a call. Without this, the observer's alert state kept every
   native call in `:outstanding-calls` (only `:tool/call-completed`
   removes one), and a `:tool-call-timeout` rule would later report it
   as timed out. Claude's `tool_use` / `tool_result` pairing is
   unchanged.
4. Codex numbers its items `item_0`, `item_1`, ... afresh in every
   `codex exec` (the exec event processor's `next_item_id` counter), so
   a planning turn's `item_1` and an implement turn's `item_1` were the
   same published call id, and the timeline named both after the later
   one. `thread.started` now parses to a heartbeat carrying
   `:invocation-id` (the thread id). The callback publishes the call ids
   that follow as `<thread id>/<item id>` until a `:done?` event ends the
   invocation. A provider that names no invocation (Claude) keeps its
   ids. The ids are qualified where they are produced, not in one
   consumer, because the observer, the timeline and anything reading
   persisted events all key on `:tool/call-id`.
5. `event-stream/timeline.clj`: a `:tool/call-completed` with neither
   `:tool/success?` nor `:tool/error` renders as `outcome unknown`
   (new message `:timeline/status-unknown`) instead of `success`. A
   Codex item still `in_progress` when its turn ended has no outcome.

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

New `components/observer/test/ai/miniforge/observer/completed_tool_call_test.clj`
feeds completed native calls through `create-streaming-callback` into the
observer's alert rules. No call stays outstanding and no timeout fires.
A Claude `tool_use` still waits for its result. Against the first commit's
`cli_output.clj` and `callbacks.clj`, it and the parser's completion
assertions fail 9 of 41 assertions.

New `components/event-stream/test/ai/miniforge/event_stream/codex_call_ids_test.clj`
covers two execs that both report `item_1`. They publish distinct ids,
and the timeline names each call correctly. It also checks that
unqualified providers keep their ids, and that a call without an outcome
renders as unknown. Against the second commit's sources, it fails.

With all commits, these pass together with `ai.miniforge.llm.interface-test`,
`ai.miniforge.observer.alerts-test` and `ai.miniforge.event-stream.interface-test`
(101 tests, 584 assertions).

## Deployment Plan

No configuration change. Codex responses gain tool names in
`:tools-called`. Each native call publishes `:agent/tool-call`,
`:agent/tool-call-started` and `:tool/call-completed`.

## Related Issues/PRs

- #2378: this is the tool-telemetry half. Token and cost reporting
  through phase failure and the summary are separate PRs.
- Left out: a heartbeat on `item.started` for long-running commands, and
  tool telemetry for backends without a stream parser (Cursor, OpenCode).
