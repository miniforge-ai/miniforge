# feat: Add schema component with Malli schemas for core domain types

## Overview

Creates the foundational `schema` component for miniforge.ai, implementing Malli schemas for all core domain types defined in the product specification. This is Phase 0 (Foundations) work that enables type validation across the system.

## Motivation

The miniforge.ai platform requires a type-safe foundation for its domain model. By defining schemas upfront:

- All components can validate data at boundaries
- Domain types are documented and enforced
- Other components can depend on `schema.interface` for validation
- The system has a single source of truth for type definitions

## Changes in Detail

### New Component: `components/schema/`

**deps.edn**
- Added Malli 0.16.4 dependency (BB-compatible)

**core.clj** - Domain schemas from miniforge.spec
- `Agent` - id, role, capabilities, memory, config
- `Task` - id, type, status, agent, inputs, outputs, parent, children, constraints, result
- `Artifact` - id, type, version, content, origin, parents, children, metadata
- `Workflow` - id, name, status, phase, priority, checkpoint, budget, consumed
- Supporting schemas: `TaskConstraints`, `TaskResult`, `ArtifactOrigin`, `WorkflowBudget`
- Enum definitions: `agent-roles`, `task-types`, `task-statuses`, `artifact-types`, `workflow-phases`, `workflow-statuses`

**logging.clj** - Logging schemas from logging.spec
- `LogEntry` - Structured log entry with context, scenario, trace, and performance fields
- `Scenario` - Test scenario definition for scenario-based testing
- Event taxonomy: `agent-events`, `loop-events`, `policy-events`, `artifact-events`, `system-events`
- Enum definitions: `log-levels`, `log-categories`, `scenario-tags`

**interface.clj** - Public API
- Schema re-exports for other components
- Generic validation: `valid?`, `validate`, `explain`
- Type-specific validators: `valid-agent?`, `valid-task?`, `valid-artifact?`, `valid-workflow?`, `valid-log-entry?`, `valid-scenario?`
- Enum vectors exported for programmatic access

**interface_test.clj** - Comprehensive test coverage
- 9 test functions, 36 assertions
- Tests for all domain types (valid and invalid cases)
- Tests for validation functions and error reporting
- Tests for enum value access

### Modified Files

**deps.edn** (root)
- Added schema component to `:dev` alias extra-paths
- Added schema component to `:test` alias for test execution

## Testing Plan

- [x] All 36 assertions pass via `poly test :dev :all-bricks`
- [x] Schema validates correct data (positive tests)
- [x] Schema rejects invalid data (negative tests)
- [x] Enum values match spec definitions
- [x] Error messages are human-readable via `explain`

## Deployment Plan

This is a foundations component with no runtime dependencies. Merge to main and it becomes available for other components to depend on.

## Related Issues/PRs

- Implements Phase 0 deliverables from `docs/specs/miniforge.spec`
- Schemas derived from `docs/specs/logging.spec`

## Architecture Notes

### Polylith Structure

```
components/schema/
├── deps.edn                 # Malli dependency
├── src/ai/miniforge/schema/
│   ├── interface.clj        # Public API (thin)
│   ├── core.clj             # Domain schemas
│   └── logging.clj          # Logging schemas
└── test/ai/miniforge/schema/
    └── interface_test.clj   # Tests
```

### Dependency Position

```
schema -> []  (depends on nothing internal)
```

Other components will depend on `ai.miniforge.schema.interface` for validation.

## Checklist

- [x] Component created via `poly create component name:schema`
- [x] Malli dependency added
- [x] Agent schema implemented
- [x] Task schema implemented
- [x] Artifact schema implemented
- [x] Workflow schema implemented
- [x] LogEntry schema implemented
- [x] Scenario schema implemented
- [x] Interface with validation functions
- [x] Tests written and passing
- [x] 3-layer file structure followed
- [x] Rich comment blocks for REPL testing
- [x] PR documentation created
