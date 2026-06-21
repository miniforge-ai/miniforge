# chore: LSP MCP Bridge Boundary Audit

## Overview

Audit `bases/lsp-mcp-bridge/src` against the Polylith requirement that bases remain boundary adapters.

## Motivation

Group 3 of `work/polylith-workflow-gates-and-scope.spec.edn` flagged `bases/lsp-mcp-bridge` as a large base that
needed manual review. Size alone is not a violation, but the base must not contain reusable product domain logic that
belongs in a component.

## Audit Scope

Reviewed every Clojure source file under `bases/lsp-mcp-bridge/src/ai/miniforge/lsp_mcp_bridge`:

- `config.clj`
- `installer.clj`
- `tasks.clj`
- `main.clj`
- `lsp/client.clj`
- `lsp/manager.clj`
- `lsp/process.clj`
- `lsp/protocol.clj`
- `mcp/protocol.clj`
- `mcp/server.clj`
- `mcp/tools.clj`

## Findings

No reusable product or business domain logic was found. The base is large because it owns a complete boundary adapter:
MCP stdio transport, LSP JSON-RPC framing, subprocess lifecycle, language-server installation/configuration, and tool
dispatch from MCP calls to LSP operations.

| Namespace | Boundary / Adapter | Delegation | Domain Logic |
| --- | --- | --- | --- |
| `main` | Process entrypoint, env read, shutdown hook, MCP server startup | Delegates to `config/load-config`, `manager/create-manager`, `server/start-server` | None |
| `config` | Reads classpath/user/project LSP config, maps file extensions to LSP language ids | None beyond local config helpers | None |
| `installer` | Detects platform, resolves binaries, downloads/extracts LSP servers | Uses `response.interface` for anomalies | None |
| `tasks` | Babashka task handlers for LSP status/install/setup and client config file writes | Delegates install work to `installer` and config loading to `config` | None |
| `lsp.protocol` | Builds/parses LSP JSON-RPC messages with Content-Length framing | None | None |
| `lsp.process` | Starts/stops LSP subprocesses and exposes streams/state | Uses `response.interface` for exceptions-as-data | None |
| `lsp.client` | Manages LSP client state, reader thread, pending requests, diagnostics buffer, high-level LSP calls | Delegates message shapes to `lsp.protocol` and process streams to `lsp.process` | None |
| `lsp.manager` | Coordinates on-demand LSP server install/start/init, open-document tracking, cleanup | Delegates install/process/client operations to local adapter namespaces | None |
| `mcp.protocol` | Builds/parses MCP newline-delimited JSON-RPC messages and MCP response payloads | None | None |
| `mcp.server` | Runs stdio MCP request loop and dispatches protocol methods | Delegates tool calls to `mcp.tools` | None |
| `mcp.tools` | Defines MCP tool schemas and maps each tool to the corresponding LSP client call | Delegates file routing to `lsp.manager` and LSP operations to `lsp.client` | None |

## Notes

The base has adapter-specific implementation details that are appropriate to keep at the boundary:

- `installer/extract-gzip` shells through `sh -c` for gzip redirection. That is a robustness/security hardening
  candidate if this adapter becomes network-facing, but it is not domain-logic bloat under this audit.
- MCP tool definitions and LSP request builders are protocol translation tables. They should only move if another
  runtime needs to share the same bridge implementation.

## Changes in Detail

- Added this audit report for Group 3.
- No runtime code changes.
- No follow-up extraction specs filed, because no Req 2 domain-logic violations were found.

## Testing Plan

- Read all source files under `bases/lsp-mcp-bridge/src`.
- `bb pre-commit`
- `bb test`
- `bb build:cli`

## Deployment Plan

Merge after CI and review comments are resolved. No runtime rollout required.

## Related Issues/PRs

- Closes Group 3 of `work/polylith-workflow-gates-and-scope.spec.edn`.
- Follows PR #1241 for Group 2.

## Checklist

- [x] Every `.clj` source under `bases/lsp-mcp-bridge/src` was reviewed.
- [x] Findings are categorized per namespace.
- [x] No domain-logic extraction is needed for this group.
- [x] Validation passes.
- [ ] CI and review comments are resolved.
