<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Releaser agent MCP integration

**Branch:** `feat/releaser-mcp-integration`
**Date:** 2026-02-28
**Dependencies:** `refactor/data-driven-mcp-artifact-server` (PR1)

## Overview

Wires the releaser agent to use the `submit_release_artifact` MCP tool, with fallback to text parsing for backward
compatibility.

## Motivation

The releaser agent previously relied on parsing raw EDN from the LLM's
text output. This is fragile and inconsistent with the implementer and
planner agents, which already use MCP artifact sessions. Adding MCP
support gives the releaser the same structured submission path.

## Changes in Detail

### `components/agent/src/ai/miniforge/agent/releaser.clj`

- Added require: `[ai.miniforge.agent.artifact-session :as artifact-session]`
- Wrapped the LLM call in `artifact-session/with-artifact-session`,
  following the exact same pattern as `implementer.clj` and `planner.clj`:
  - Passes `{:mcp-config (:mcp-config-path session)}` into LLM opts
  - After LLM call: if `artifact` is non-nil, uses it; else falls through to `parse-release-response` ->
    `make-fallback-artifact`
- Added `:releaser/mcp-artifact-received` log event when artifact found
- Preserves `response/success` and `response/error` return convention (unlike tester/implementer which return raw maps)
- No changes to the no-LLM-client fallback path (backward compat for testing)

### `components/agent/resources/prompts/releaser.edn`

- Added "Preferred Output Method" section before the closing `Remember:` paragraph
- Instructs LLM to use `submit_release_artifact` when available with
  params: `branch_name`, `commit_message`, `pr_title`, `pr_description`,
  `files_summary`
- Falls back to EDN output if tool unavailable

## Testing Plan

- Invoke releaser with no LLM backend -> falls through to fallback artifact (backward compat)
- With `:claude` backend and MCP config -> should use MCP tool, artifact returned via session
- Verify release artifact has proper UUID and timestamp after MCP round-trip
- Verify `response/success` wrapping is preserved

## Deployment Plan

Merge after PR1 (data-driven MCP server). Independent of PR2 (tester).

## Related Issues/PRs

- **Depends on:** PR1 (data-driven MCP artifact server)
- **Parallel with:** PR2 (tester MCP integration)
- **Pattern from:** implementer.clj, planner.clj MCP integration

## Checklist

- [x] `artifact-session` require added to releaser.clj
- [x] LLM call wrapped with `with-artifact-session`
- [x] MCP artifact preferred over text parsing
- [x] Fallback to text parsing preserved
- [x] `response/success` / `response/error` convention preserved
- [x] `:releaser/mcp-artifact-received` log event added
- [x] Prompt updated with MCP tool instructions
