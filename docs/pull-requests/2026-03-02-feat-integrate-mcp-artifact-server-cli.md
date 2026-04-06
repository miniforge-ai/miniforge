<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Integrate MCP artifact server into miniforge CLI

## Overview

Adds `miniforge mcp-serve` as a CLI subcommand so the MCP artifact server runs
as part of miniforge itself, eliminating fragile CWD-relative path resolution
and manual classpath construction.

## Motivation

The MCP artifact server previously ran as a separate Babashka subprocess spawned
via `bb -cp components/mcp-artifact-server/src -m ...`. This required:

- CWD to be the project root (broke when invoked from elsewhere)
- Manual classpath assembly (src + resources directories)
- Multiple fallback resolution strategies (component path, legacy script, MINIFORGE_HOME)

By putting the MCP server on the CLI's classpath and exposing it as a command,
`artifact_session.clj` can generate MCP configs that simply point back to
`miniforge mcp-serve`, regardless of where the process is running.

## Changes in Detail

### 1. `bb.edn` — Add MCP component to CLI classpath

Added `components/mcp-artifact-server/src` and `resources` to the `miniforge`
task's `:extra-paths`.

### 2. `bases/cli/src/ai/miniforge/cli/main.clj` — New `mcp-serve` command

- `mcp-serve-cmd` handler using `requiring-resolve` for lazy loading
- Dispatch table entry with `--artifact-dir` / `-d` spec

### 3. `components/agent/src/.../artifact_session.clj` — Simplified config generation

Replaced `resolve-server-script`, `resolve-server-classpath`, and the old
`server-command` (60+ lines of path resolution) with:

- `resolve-miniforge-command` — 3-tier resolution:
  1. `MINIFORGE_CMD` env var (explicit override)
  2. `miniforge` on PATH (installed binary)
  3. `["bb" "miniforge"]` (dev mode fallback)
- `server-command` — returns `{:command ... :args [...]}` for MCP config

### 4. `scripts/test-mcp-artifact-server.bb` — Simplified test server startup

Replaced manual classpath construction with `bb miniforge mcp-serve --artifact-dir`.

## Testing Plan

- [x] `bb miniforge mcp-serve` without `--artifact-dir` prints error and exits 1
- [x] `bb test:mcp` — all 50 MCP integration tests pass
- [ ] `bb test:all` — no regressions
- [ ] `bb miniforge run work/<spec>` — workflows produce artifacts end-to-end

## Deployment Plan

Merge to main. No breaking changes — the MCP server interface is internal.

## Related Issues/PRs

- PR #233 (this branch: `fix/mcp-artifact-persistence`)
- Prior: `2026-02-28-refactor-data-driven-mcp-artifact-server.md`
- Prior: `2026-02-28-test-mcp-artifact-server.md`

## Checklist

- [x] MCP component on CLI classpath
- [x] `mcp-serve` CLI subcommand wired up
- [x] `artifact_session.clj` uses `miniforge mcp-serve`
- [x] Test script uses `bb miniforge mcp-serve`
- [x] 50/50 MCP tests passing
- [ ] Full test suite passes
