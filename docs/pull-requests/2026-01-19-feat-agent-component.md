# PR: Agent Component (Protocol, Memory, Core)

**Branch:** `feat/agent-component`
**Date:** 2026-01-19

## Summary

Implements the core `agent` component for miniforge.ai. Provides the foundational agent protocol,
memory management with token windowing, and execution infrastructure that specialized agents build upon.

## Changes

### New Files

- `components/agent/deps.edn` - Component dependencies (schema, logging)
- `components/agent/src/ai/miniforge/agent/protocol.clj` - Core protocols with:
  - `Agent` protocol: `invoke`, `validate`, `repair` methods
  - `AgentLifecycle` protocol: `init`, `status`, `shutdown` methods
  - `AgentExecutor` protocol: Full execution lifecycle
  - `LLMBackend` protocol: Dependency injection for LLM calls
- `components/agent/src/ai/miniforge/agent/memory.clj` - Memory management with:
  - Conversation context storage
  - Token windowing/trimming for context limits
  - Memory summarization helpers
- `components/agent/src/ai/miniforge/agent/core.clj` - Implementation with:
  - `BaseAgent` record implementing Agent protocol
  - Execution flow: init → invoke → validate → repair (if needed) → complete
  - Artifact type mappings and metrics tracking
- `components/agent/src/ai/miniforge/agent/interface.clj` - Public API exports
- `components/agent/test/ai/miniforge/agent/*_test.clj` - Comprehensive test suite

### Modified Files

- `deps.edn` - Added agent component paths to dev/test aliases

## Design Decisions

1. **Protocol-based Architecture**: Agent behavior defined via protocols, allowing diverse
   agent implementations while maintaining consistent interfaces.

2. **Separation of Concerns**: Protocol definition, memory management, and execution logic
   are in separate namespaces for clarity and testability.

3. **Token Windowing**: Memory module supports trimming conversation history to fit context
   limits, essential for long-running agent sessions.

4. **Metrics Tracking**: Every agent execution tracks tokens, cost, and duration.

## Testing

```bash
clojure -M:poly test :dev
# All assertions pass for agent component
```

## Dependencies

- `ai.miniforge/schema` - Type definitions (local)
- `ai.miniforge/logging` - Structured logging (local)
