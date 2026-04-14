<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Add MCP (Model Context Protocol) Integration to Extensibility Spec

**PR**: [#6](https://github.com/miniforge-ai/miniforge/pull/6)
**Branch**: `feat/mcp-integration`
**Date**: 2026-01-18
**Status**: Open

## Overview

Adds MCP (Model Context Protocol) compatibility to the extensibility specification, enabling native agent integration
with MCP-compatible clients (Claude Desktop, Cursor, etc.) without custom tool-calling logic.

## Motivation

Currently, miniforge tools are defined with a custom protocol. By making them MCP-compatible:

- Agents using MCP (Claude Desktop, Cursor, etc.) can discover and invoke tools natively
- No need to implement custom tool-calling logic
- Tools can be composed with other MCP servers
- Future-proof as the MCP ecosystem grows

## Changes in Detail

### Modified Files

| File | Changes |
|------|---------|
| `docs/specs/extensibility.spec` | Added ~168 lines of MCP integration documentation |

### Documentation Updates

1. **Section 3 (Tool Protocol)**: Added MCP compatibility note at the top
2. **Section 5.2 (Agent Tool Discovery)**: Expanded with:
   - Native tool format (existing)
   - MCP tool format (new JSON Schema examples)
   - Metadata mapping table (miniforge → MCP fields)
   - MCP server implementation protocol
3. **New Section 9 (MCP Integration)**:
   - Rationale for MCP support
   - MCP server mode (expose miniforge tools)
   - MCP client mode (consume external MCP servers)
   - Implementation requirements
4. **Section 10 (Deliverables)**: Updated to include:
   - Malli → JSON Schema converter
   - MCP server implementation
   - MCP client implementation
5. **Section 11 (Open Questions)**: Added questions about MCP transport and versioning

## Testing Plan

- [x] Documentation reviewed for clarity
- [x] MCP format examples validated against MCP spec
- [ ] Implementation will be tested in future PRs

## Deployment Plan

Documentation-only change. No deployment needed.

## Related Issues/PRs

- Enables integration with MCP ecosystem
- Foundation for future MCP server/client implementation
- Related to extensibility architecture in `docs/specs/extensibility.spec`

## Checklist

- [x] Documentation updated
- [x] Examples provided
- [x] Deliverables updated
- [x] Open questions documented
- [x] PR created
