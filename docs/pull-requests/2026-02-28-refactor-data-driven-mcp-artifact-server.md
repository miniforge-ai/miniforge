<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: Data-driven MCP artifact server + generic session handling

**Branch:** `refactor/data-driven-mcp-artifact-server`
**Date:** 2026-02-28
**Dependencies:** None (base PR)

## Overview

Refactors the MCP artifact server from bespoke per-tool handlers to a
table-driven design, and makes `artifact_session.clj` handle any artifact
type generically. This is the foundation for wiring the tester and releaser
agents to MCP.

## Motivation

The MCP server had two hand-written handler functions
(`handle-submit-code-artifact`, `handle-submit-plan`) with duplicated
validation and persistence logic. Adding a new tool meant writing another
bespoke function. Meanwhile, `artifact_session.clj` hardcoded `:code/id`,
`:plan/id`, `:code/created-at`, and `:plan/created-at` for UUID/instant
parsing, so new artifact types would silently skip conversion.

## Changes in Detail

### `scripts/mcp-artifact-server.bb`

- Replaced `handle-submit-code-artifact` and `handle-submit-plan` with a `tool-registry` map keyed by tool name
- Each registry entry contains `{:tool-def, :required-params, :build-artifact}`
- Added generic `handle-tool-call` that: looks up tool in registry,
  validates required params, calls `:build-artifact`, writes to
  `artifact.edn`
- `tool-definitions` is now derived: `(mapv :tool-def (vals tool-registry))`
- Added two new tools:
  - `submit_test_artifact` — accepts `files`, `summary`, `type`,
    `framework`, `coverage`; counts assertions/test cases from content
  - `submit_release_artifact` — accepts `branch_name`, `commit_message`,
    `pr_title`, `pr_description`, `files_summary`
- Server version bumped to 2.0.0

### `components/agent/src/ai/miniforge/agent/artifact_session.clj`

- Replaced hardcoded key checks in `parse-uuid-strings` with pattern-based detection:
  - Any key ending in `/id` with UUID-shaped string value -> `java.util.UUID`
  - Any key ending in `/created-at` with ISO instant string -> `java.util.Date`
  - Any vector of maps -> recurse into each map (handles `:plan/tasks`, `:test/files`, etc.)
- Extracted `uuid-str?`, `instant-str?`, `key-ends-with?` helpers

## Testing Plan

- Verify existing `submit_code_artifact` and `submit_plan` tools still work via stdin/stdout JSON-RPC
- Test new `submit_test_artifact` writes correct EDN with assertion/case counts
- Test new `submit_release_artifact` writes correct EDN with all required fields
- Verify `parse-uuid-strings` converts `:test/id`, `:release/id`, `:test/created-at`, `:release/created-at` correctly
- Verify nested maps in vectors (e.g. `:test/files`, `:plan/tasks`) are recursed into

## Deployment Plan

Merge before PR2 (tester MCP) and PR3 (releaser MCP) since both depend on this.

## Related Issues/PRs

- **Blocks:** PR2 (tester MCP integration), PR3 (releaser MCP integration)
- **Prior art:** Original `submit_code_artifact` / `submit_plan` implementation

## Checklist

- [x] Tool registry replaces bespoke handlers
- [x] Generic `handle-tool-call` with validation
- [x] `submit_test_artifact` tool added
- [x] `submit_release_artifact` tool added
- [x] `parse-uuid-strings` uses pattern-based detection
- [x] Backward compatible with existing code/plan artifacts
