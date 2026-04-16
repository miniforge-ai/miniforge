<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix stale Codex MCP cleanup leaving invalid transport config

**PR:** [#554](https://github.com/miniforge-ai/miniforge/pull/554)
**Branch:** `fix/codex-invalid-transport-cleanup`

## Context

Miniforge writes a project-local `.codex/config.toml` during MCP-backed agent
sessions. Cleanup was only removing the root `[mcp_servers.artifact]` block,
not nested tables under the same subtree.

That leaves behind config fragments such as:

- `[mcp_servers.artifact.tools.context_read]`

Current Codex builds reject that orphaned nested table at startup with:

- `Error loading config.toml: invalid transport in mcp_servers.artifact`

The effect is that opening Codex or the Codex desktop app from a Miniforge repo
can fail until the broken local config is manually repaired.

## What changed

- Added `strip-codex-artifact-config` in `artifact_session.clj` to remove the
  full `mcp_servers.artifact` TOML subtree, including nested tables.
- Updated `write-codex-mcp-config!` to rewrite the config from preserved
  non-artifact content plus a fresh artifact block, instead of replacing only
  the top-level server table via regex.
- Updated `cleanup-codex-mcp-config!` to remove nested artifact tables and keep
  unrelated project config intact.
- Added a regression test covering cleanup of a config file that contains both
  `[mcp_servers.artifact]` and
  `[mcp_servers.artifact.tools.context_read]`.

## Verification

- `codex mcp list` succeeds from the repaired local repo after removing the
  stale nested artifact table
- `clojure -M:dev:test -e "(require 'clojure.test 'ai.miniforge.agent.artifact-session-test) ..."`
  runs the focused artifact session test namespace successfully

## Risk

This change is intentionally narrow:

- It only affects Codex project-local MCP config generation and cleanup
- It preserves unrelated TOML sections outside the `mcp_servers.artifact`
  subtree
- It adds a regression test for the exact stale-config shape that caused the
  startup failure
