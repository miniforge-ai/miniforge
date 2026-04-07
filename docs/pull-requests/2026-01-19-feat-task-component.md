# PR: Task Component

**Branch:** `feat/task-component`
**Date:** 2026-01-19

## Summary

Implements the `task` component for miniforge.ai. Provides task lifecycle management with
state machine, priority queue for scheduling, and dependency graph for workflow orchestration.

## Changes

### New Files

- `components/task/deps.edn` - Component dependencies (schema, logging, malli)
- `components/task/src/ai/miniforge/task/core.clj` - Task CRUD with:
  - In-memory atom-based storage
  - State machine: `pending → running → completed/failed/blocked`
  - Valid state transitions enforcement
  - Task metadata and timing information
- `components/task/src/ai/miniforge/task/queue.clj` - Priority queue with:
  - Priority calculation: workflow priority × 100 + age × 0.001 + ready bonus
  - Role-based filtering (planner, implementer, tester, etc.)
  - Queue operations: enqueue, dequeue, peek, update-priority
- `components/task/src/ai/miniforge/task/graph.clj` - Dependency graph with:
  - Cycle detection using depth-first search
  - Topological sort for execution order
  - Ready task identification
  - Blocker analysis
- `components/task/src/ai/miniforge/task/interface.clj` - Public API exports
- `components/task/test/ai/miniforge/task/*_test.clj` - Comprehensive test suite

### Modified Files

- `deps.edn` - Added task component paths to dev/test aliases

## Design Decisions

1. **Priority Formula**: Combines workflow priority (urgency), task age (fairness),
   and dependency readiness (efficiency).

2. **State Machine**: Strict state transitions prevent invalid task states and
   enable clear workflow reasoning.

3. **Cycle Detection**: Graph module prevents circular dependencies that would
   cause deadlocks in task execution.

4. **Role Mapping**: Queue supports filtering by agent role, enabling specialized
   agents to claim appropriate tasks.

## Testing

```bash
clojure -M:poly test :dev
# All assertions pass for task component
```

## Dependencies

- `ai.miniforge/schema` - Type definitions (local)
- `ai.miniforge/logging` - Structured logging (local)
- `metosin/malli` - Schema validation
