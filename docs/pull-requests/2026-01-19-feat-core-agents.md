<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR: Core Agents (Planner, Implementer, Tester)

**Branch:** `feat/core-agents`
**Date:** 2026-01-19

## Summary

Implements the core specialized agents for miniforge.ai: Planner, Implementer, and Tester.
Each agent follows the Agent protocol and produces structured artifacts that flow through
the inner loop validation cycle.

## Changes

### New Files

- `components/agent/deps.edn` - Component dependencies (schema, logging, malli)
- `components/agent/src/ai/miniforge/agent/core.clj` - Base infrastructure with:
  - `Agent` protocol implementation
  - `BaseAgent` record with invoke/validate/repair
  - `cycle-agent` for full validation cycles
  - `make-validator` for Malli-based validation
- `components/agent/src/ai/miniforge/agent/planner.clj` - Planner agent with:
  - System prompt for plan generation
  - Plan schema: tasks, dependencies, acceptance criteria, complexity
  - Validation: no cycles, valid task references
  - Utilities: `task-dependency-order`, `plan-summary`
- `components/agent/src/ai/miniforge/agent/implementer.clj` - Implementer agent with:
  - System prompt for code generation
  - Code artifact schema: files, actions, language, tests-needed
  - Validation: no empty creates, no duplicate paths
  - Utilities: `files-by-action`, `total-lines`, `code-summary`
- `components/agent/src/ai/miniforge/agent/tester.clj` - Tester agent with:
  - System prompt for test generation
  - Test artifact schema: files, type, coverage, assertions
  - Validation: test file naming, assertion count
  - Utilities: `coverage-meets-threshold?`, `tests-by-path`, `test-summary`
- `components/agent/src/ai/miniforge/agent/interface.clj` - Public API with:
  - Agent creation: `create-planner`, `create-implementer`, `create-tester`
  - Generic creation: `create-agent` by role keyword
  - Protocol functions: `invoke`, `validate`, `repair`
  - Pipeline execution: `run-pipeline`
- `components/agent/test/ai/miniforge/agent/*_test.clj` - Comprehensive test suite

### Modified Files

- `deps.edn` - Added agent component paths to dev/test aliases

## Design Decisions

1. **BaseAgent Pattern**: All agents share common infrastructure (logging, metrics,
   validation cycle) through the BaseAgent record.

2. **System Prompts**: Each agent has a specialized system prompt defining its role,
   input/output format, and best practices.

3. **Malli Schemas**: All artifacts are validated against Malli schemas, enabling
   structured repair when validation fails.

4. **Repair Strategies**: Each agent includes repair logic for common validation
   failures (missing IDs, empty content, naming issues).

## Agent Responsibilities

| Agent | Input | Output | Validates |
|-------|-------|--------|-----------|
| Planner | Spec/requirements | Plan with tasks | No cycles, valid refs |
| Implementer | Plan/task | Code artifact | No empty creates |
| Tester | Code artifact | Test artifact | File naming, coverage |

## Testing

```bash
clojure -M:poly test :dev
# All assertions pass for agent component
```

## Dependencies

- `ai.miniforge/schema` - Type definitions (local)
- `ai.miniforge/logging` - Structured logging (local)
- `metosin/malli` - Schema validation
