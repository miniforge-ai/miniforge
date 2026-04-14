<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Add Schema Component with Malli Schemas

**PR**: [#1](https://github.com/miniforge-ai/miniforge/pull/1)
**Branch**: `feat/schema-component`
**Date**: 2026-01-18
**Status**: Merged

## Overview

Add the foundational `schema` component containing Malli schemas for all core domain types. This is Layer 0
(Foundations) in the stratified design.

## Motivation

All other components need a shared vocabulary of domain types. By defining schemas in a dedicated component:

- Types are reusable across the codebase
- Validation is consistent
- Documentation is centralized
- Changes to domain model are localized

## Changes in Detail

### New Files

| File | Purpose |
|------|---------|
| `components/schema/src/ai/miniforge/schema/core.clj` | Core domain schemas: Agent, Task, Workflow, Artifact |
| `components/schema/src/ai/miniforge/schema/logging.clj` | Logging schemas: LogEntry, LogLevel, EventType |
| `components/schema/src/ai/miniforge/schema/interface.clj` | Public API exposing all schemas |
| `components/schema/deps.edn` | Component dependencies (Malli) |

### Schemas Defined

**Core Domain**:

- `Agent` - Agent identity and capabilities
- `Task` - Unit of work
- `Workflow` - Collection of tasks
- `Artifact` - Build outputs, code, etc.
- `AgentRole` - Enum of agent roles
- `TaskType` - Enum of task types
- `WorkflowStatus` - State machine for workflows

**Logging**:

- `LogLevel` - :trace, :debug, :info, :warn, :error, :fatal
- `EventType` - Categorized event types
- `LogEntry` - Full structured log record

## Testing Plan

- [x] Component compiles
- [x] Schemas validate correctly
- [x] Interface exposes all public schemas

## Deployment Plan

Merged to main. Foundation for all subsequent components.

## Related Issues/PRs

- Prerequisite for #2 (logging component)
- Implements schemas from `docs/specs/miniforge.spec`
