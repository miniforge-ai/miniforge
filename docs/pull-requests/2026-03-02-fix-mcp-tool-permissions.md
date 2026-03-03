# fix: Pass --allowedTools to Claude CLI for MCP artifact tools

## Overview

Fix MCP artifact submission by passing `--allowedTools` to the Claude CLI
when MCP tools are configured. Without explicit tool permissions, the inner
Claude session outputs artifacts as text instead of calling MCP tools.

## Motivation

After integrating the MCP artifact server as `miniforge mcp-serve` (PR #233),
the `--mcp-config` flag was correctly passed to the Claude CLI but the MCP
tools were never actually called. In `-p` (print) mode, Claude CLI requires
explicit `--allowedTools` to permit tool use — otherwise it just produces text.

This manifested as repeated `WARN: artifact file not found` during spec runs.

## Changes in Detail

### 1. `artifact_session.clj` — Expose allowed tool names on session

- Added `mcp-server-name` and `mcp-tool-names` constants
- `write-mcp-config!` now populates `:mcp-allowed-tools` on the session
  with fully-qualified names (e.g. `mcp__artifact__submit_code_artifact`)
- Backend-agnostic: tool names are data, not CLI flags

### 2. Agent files — Thread `:mcp-allowed-tools` to LLM layer

Updated all four agents to include `:mcp-allowed-tools` in `mcp-opts`:

- `implementer.clj`
- `planner.clj`
- `tester.clj`
- `releaser.clj`

### 3. `llm_client.clj` — Add `--allowedTools` to Claude backend

The `:claude` backend's `args-fn` now passes
`--allowedTools <comma-separated-tools>` when `mcp-allowed-tools` is present.
Other backends (codex, openai, ollama) ignore the key — no changes needed.

### 4. `artifact_session_test.clj` — Update test for new session shape

Updated `write-mcp-config-test` to expect `:mcp-allowed-tools` on the
returned session map.

## Testing Plan

- [x] `bb test` — 321 tests, 0 failures
- [x] `bb test:mcp` — 50/50 MCP tests pass
- [ ] `bb miniforge run work/finish-yc-mvp.spec.edn` — artifacts captured via MCP

## Deployment Plan

Merge to main. Internal change, no user-facing API changes.

## Related Issues/PRs

- PR #233: `feat(mcp): integrate artifact server as miniforge mcp-serve`
- Root cause: Claude CLI `-p` mode requires explicit `--allowedTools` for MCP tools

## Checklist

- [x] Session exposes `:mcp-allowed-tools`
- [x] All 4 agents thread tool names to LLM
- [x] Claude backend passes `--allowedTools`
- [x] Tests updated and passing
- [x] Backend-agnostic design (non-Claude backends unaffected)
