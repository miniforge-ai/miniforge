# PR: Tool Component

**Branch:** `feat/tool-component`
**Date:** 2026-01-19

## Summary

Implements the `tool` component, completing Phase 0 (Foundations). Provides the tool protocol
and registry for agents to execute external capabilities.

## Changes

### New Files

- `components/tool/deps.edn` - Component dependencies (schema, logging)
- `components/tool/src/ai/miniforge/tool/core.clj` - Core implementation with:
  - `Tool` protocol for executable tools
  - `ToolRegistry` protocol for registration and lookup
  - `FunctionTool` record for function-based tools
  - `AtomRegistry` implementation with logging
  - Parameter validation utilities
- `components/tool/src/ai/miniforge/tool/interface.clj` - Public API with:
  - `create-tool` - Create function-based tools
  - `create-registry` - Create tool registries
  - `register!` / `unregister!` - Registry management
  - `execute` / `execute-by-id` - Tool execution
  - Response helpers: `success?`, `get-result`, `get-error`
- `components/tool/test/ai/miniforge/tool/interface_test.clj` - 38 test assertions

### Modified Files

- `deps.edn` - Added tool component paths to dev/test aliases

## Design Decisions

1. **Protocol-based**: Both Tool and ToolRegistry are protocols, allowing custom implementations.

2. **Namespaced IDs**: Tool IDs must be namespaced keywords (e.g., `:tools/echo`) to prevent
   collisions and enable categorization.

3. **Parameter Validation**: Built-in validation for required parameters before execution.

4. **Error Handling**: Exceptions in handlers are caught and returned as structured error
   responses rather than thrown.

## Testing

```bash
clojure -M:poly test :dev
# 38 assertions pass for tool component
# 138 total assertions across all components
```

## Dependencies

- `ai.miniforge/schema` - Type definitions (local)
- `ai.miniforge/logging` - Structured logging (local)
