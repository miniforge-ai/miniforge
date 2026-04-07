# PR: LLM Component

**Branch:** `feat/llm-component`
**Date:** 2026-01-18

## Summary

Implements the `llm` component for Phase 0 (Foundations), providing a CLI-based LLM client
abstraction that supports multiple backends (Claude CLI, Cursor CLI, echo for testing).

## Changes

### New Files

- `components/llm/deps.edn` - Component dependencies (babashka/process, logging)
- `components/llm/src/ai/miniforge/llm/core.clj` - Core implementation with:
  - Backend configuration registry (claude, cursor, echo)
  - `LLMClient` protocol with `complete*` and `get-config`
  - `CLIClient` record implementing the protocol
  - Message building and response parsing utilities
  - Mock execution functions for testing
- `components/llm/src/ai/miniforge/llm/interface.clj` - Public API with:
  - `create-client` - Create client with configurable backend
  - `mock-client` - Create mock client for testing
  - `complete` - Send completion request
  - `chat` - Convenience function for single-turn chat
  - Response helpers: `success?`, `get-content`, `get-error`
- `components/llm/test/ai/miniforge/llm/interface_test.clj` - 28 test assertions

### Modified Files

- `deps.edn` - Added llm component paths to dev/test aliases

## Design Decisions

1. **CLI Backend Architecture**: Uses CLI tools (claude, cursor) instead of direct HTTP API
   calls, reducing complexity and leveraging existing authentication.

2. **Pluggable Execution**: The `exec-fn` parameter allows injecting custom execution
   functions, enabling comprehensive testing without real CLI calls.

3. **Backend Registry**: Centralized backend configuration makes adding new CLI backends straightforward.

4. **Echo Backend**: Built-in echo backend for integration testing that doesn't require external tools.

## Testing

```bash
clojure -M:poly test :dev
# 28 assertions pass for llm component
# 100 total assertions across all components
```

## Dependencies

- `babashka/process` 0.5.22 - Process execution
- `ai.miniforge/logging` - Structured logging (local)
