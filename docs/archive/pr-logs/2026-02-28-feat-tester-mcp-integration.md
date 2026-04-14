<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Tester agent MCP integration

**Branch:** `feat/tester-mcp-integration`
**Date:** 2026-02-28
**Dependencies:** `refactor/data-driven-mcp-artifact-server` (PR1)

## Overview

Wires the tester agent to use the `submit_test_artifact` MCP tool, with fallback to text parsing for backward
compatibility.

## Motivation

The tester agent previously relied on parsing raw EDN or extracting code
blocks from the LLM's text output. This is fragile and inconsistent with
the implementer and planner agents, which already use MCP artifact sessions.
Adding MCP support gives the tester the same structured submission path.

## Changes in Detail

### `components/agent/src/ai/miniforge/agent/tester.clj`

- Added require: `[ai.miniforge.agent.artifact-session :as artifact-session]`
- Wrapped the LLM call in `artifact-session/with-artifact-session`,
  following the exact same pattern as `implementer.clj` and `planner.clj`:
  - Passes `{:mcp-config (:mcp-config-path session)}` into LLM opts
  - After LLM call: if `artifact` is non-nil, uses it; else falls through
    to `parse-test-response` -> `extract-test-code-blocks` ->
    `make-fallback-tests`
- Added `:tester/mcp-artifact-received` log event when artifact found
- No changes to the no-LLM-client fallback path (backward compat for testing)

### `components/agent/resources/prompts/tester.edn`

- Added "Preferred Output Method" section before the closing `Remember:` paragraph
- Instructs LLM to use `submit_test_artifact` when available with params: `files`, `summary`, `type`, `framework`,
  `coverage`
- Falls back to EDN output if tool unavailable

## Testing Plan

- Invoke tester with no LLM backend (`:echo` or nil) -> falls through to fallback tests (backward compat)
- With `:claude` backend and MCP config -> should use MCP tool, artifact returned via session
- Verify test artifact has proper UUID and timestamp after MCP round-trip
- Verify assertion/case counts are populated from MCP artifact

## Deployment Plan

Merge after PR1 (data-driven MCP server). Independent of PR3 (releaser).

## Related Issues/PRs

- **Depends on:** PR1 (data-driven MCP artifact server)
- **Parallel with:** PR3 (releaser MCP integration)
- **Pattern from:** implementer.clj, planner.clj MCP integration

## Checklist

- [x] `artifact-session` require added to tester.clj
- [x] LLM call wrapped with `with-artifact-session`
- [x] MCP artifact preferred over text parsing
- [x] Fallback to text parsing preserved
- [x] `:tester/mcp-artifact-received` log event added
- [x] Prompt updated with MCP tool instructions
