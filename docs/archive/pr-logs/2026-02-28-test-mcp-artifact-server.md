<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR: MCP Artifact Server + Artifact Session Tests

**Branch:** `test/mcp-artifact-server`
**Date:** 2026-02-28
**Type:** Test

## Context

During dogfooding, miniforge hung on the 2nd implement phase.
The event stream showed the Claude subprocess called
`submit_code_artifact` via MCP but the process stalled with
no events for 8+ minutes. The MCP artifact server and artifact
session module had zero test coverage.

## Root Cause Analysis

Writing these tests revealed **two latent hang vectors**
in the MCP server:

### 1. Silent nil-response on `when-let` (mcp-artifact-server.bb:409)

```clojure
(when-let [result (dispatch method params)]
  (write-response id result))
```

If `dispatch` returns `nil` for a **request** (a JSON-RPC
message with an `id` field), no response is ever written.
The client blocks forever waiting for a reply. Today
`dispatch` returns `nil` for `notifications/initialized`
and `notifications/cancelled`. If the Claude CLI ever sends
one of these with an `id` (treating it as a request rather
than a notification), the server silently swallows it and the
caller deadlocks.

**Fix (follow-up):** Replace `when-let` with `let` +
explicit nil check, or always send a response for requests.

### 2. Stderr pipe buffer deadlock (llm_client.clj:390)

```clojure
process (apply p/process {:err :string :in empty-stdin} cmd)
```

The Claude CLI subprocess is spawned with `:err :string`,
which means babashka buffers the entire stderr stream in
memory and only reads it after the process exits. The Claude
CLI in turn spawns the MCP server, which writes diagnostic
messages to stderr on every tool call
(`"Artifact written: <path>"`). If the stderr pipe buffer
fills (64KB on macOS) before the parent drains it, the MCP
server blocks on its stderr `println`, the Claude CLI blocks
waiting for the MCP server's stdout response, and miniforge
blocks waiting for the Claude CLI's stdout. Classic
three-process pipe deadlock.

**Fix (follow-up):** Drain stderr asynchronously
(e.g., `:err :inherit` or a separate reader thread),
or suppress MCP server diagnostic output.

## Changes

| File | Type | Description |
|------|------|-------------|
| `components/agent/test/.../artifact_session_test.clj` | New | 10 unit tests for session lifecycle, MCP config, artifact parsing, UUID conversion, cleanup, macro |
| `scripts/test-mcp-artifact-server.bb` | New | 13 test groups / 50 assertions via subprocess JSON-RPC: all 4 tools, error codes, notifications, 100KB payload, rapid sequential calls |
| `projects/miniforge/test/.../artifact_server_integration_test.clj` | New | 3 integration tests: full round-trip, clean shutdown, concurrent session isolation |
| `bb.edn` | Edit | Added `test:mcp` task, added integration test namespace to `test:integration`, updated `test:all` |
| `docs/pull-requests/2026-02-28-test-mcp-artifact-server.md` | New | This document |

## Verification

```bash
bb test:mcp          # 50/50 passing — MCP server handler tests
bb test              # Brick unit tests (includes artifact_session_test)
bb test:integration  # Project integration tests (includes MCP round-trip)
bb test:all          # Full suite
```

## Follow-up Items

1. **Fix `when-let` in MCP server main loop** — replace with unconditional response for requests
2. **Fix stderr buffering in `stream-exec-fn`** — drain stderr asynchronously to prevent pipe deadlock
3. **Add adaptive timeout to MCP tool calls** — detect stalls earlier than the current 10-minute ceiling
