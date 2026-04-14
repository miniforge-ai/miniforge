<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Universal MCP config for all CLI backends

## Overview

Extend MCP artifact server integration to work with all three CLI backends
(Claude, Codex, Cursor) by writing backend-native config files. Previously
only Claude connected to the MCP artifact server.

## Motivation

MCP is a cross-tool standard supported by Claude, Codex, and Cursor Agent,
but each has a different config mechanism:

| CLI | Config location | Format |
|-----|----------------|--------|
| Claude | `--mcp-config <file>` flag | JSON |
| Codex | `.codex/config.toml` (project-scoped) | TOML |
| Cursor | `.cursor/mcp.json` (project-scoped) | JSON |

After PR #233 integrated the MCP artifact server and PR #234 (initial commit)
fixed `--allowedTools` for Claude, the Codex and Cursor backends still had
no MCP connectivity. This change completes the picture.

## Changes in Detail

### 1. `artifact_session.clj` — Write all config formats + cleanup

Added two private helpers:

- `write-codex-mcp-config!` — creates/updates `.codex/config.toml` with
  `[mcp_servers.artifact]` block (append or replace existing block)
- `write-cursor-mcp-config!` — creates/updates `.cursor/mcp.json` with
  `mcpServers.artifact` entry (merge into existing JSON)

Updated `write-mcp-config!` to call both and track paths as
`:mcp-cleanup-files` on the session.

Added cleanup helpers:

- `cleanup-codex-mcp-config!` — removes `[mcp_servers.artifact]` block,
  deletes file if empty
- `cleanup-cursor-mcp-config!` — removes `artifact` key from mcpServers,
  deletes file if empty

Updated `cleanup-session!` to call both cleanup helpers, preserving
any pre-existing config in those files.

### 2. `llm_client.clj` — Fix Cursor backend

- Changed `:cmd` from `"cursor-cli"` to `"agent"` (correct binary name)
- Changed `:args-fn` to use `-p` flag and pass `--approve-mcps` when
  MCP tools are configured (auto-approves MCP in headless mode)
- Codex backend: no changes needed (reads `.codex/config.toml` from CWD)

### 3. `artifact_session_test.clj` — Test new config formats

Added tests:

- `write-codex-mcp-config-test` — verifies TOML content and append behavior
- `write-cursor-mcp-config-test` — verifies JSON structure and merge behavior
- `write-mcp-config-tracks-cleanup-files-test` — verifies `:mcp-cleanup-files`
- Extended `cleanup-session-test` with 3 new scenarios:
  - Preserves other config in `.codex/config.toml` after cleanup
  - Preserves other servers in `.cursor/mcp.json` after cleanup
  - Deletes empty config files after cleanup

## Testing Plan

- [x] `bb test` — 324 tests, 1827 assertions, 0 failures
- [x] `bb test:mcp` — 50/50 MCP tests pass
- [ ] Manual: run spec with Codex backend, verify artifact captured
- [ ] Manual: run spec with Cursor backend, verify artifact captured

## Deployment Plan

Merge to main. Internal change, no user-facing API changes.

## Related Issues/PRs

- PR #233: `feat(mcp): integrate artifact server as miniforge mcp-serve`
- PR #234 initial commit: `--allowedTools` fix for Claude backend

## Checklist

- [x] All three backends get MCP config written
- [x] Cleanup preserves pre-existing config
- [x] Cleanup deletes empty config files
- [x] Cursor backend uses correct binary name (`agent`)
- [x] Tests cover write + merge + cleanup scenarios
- [x] All tests passing (324 + 50 MCP)
