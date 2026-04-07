# Orchestrator Architecture Analysis

## Context

The founder-prior-art document describes miniforge.ai's architecture with distinct components:

| Component | Responsibility |
|-----------|----------------|
| **Operator** | Meta-agent managing self-improvement loop and all subflows |
| **Control Plane** | Orchestrates agents, artifacts, policies; scheduling, coordination, budget, escalation |
| **Workflow** | SDLC delivery (Plan → Design → Implement → Verify → Review → Release → Observe) |
| **Inner Loop** | Generate → Validate → Repair (per task/change) |
| **Meta Loop** | Self-improvement of the process itself |

## Current Implementation

The `orchestrator` component currently combines several concerns:

```
orchestrator/
├── protocol.clj      # Orchestrator, TaskRouter, BudgetManager, KnowledgeCoordinator
├── core.clj          # SimpleOrchestrator, SimpleTaskRouter, SimpleBudgetManager, etc.
└── interface.clj     # Public API
```

### What's Implemented

| Concept | Implementation | Notes |
|---------|---------------|-------|
| Control Plane | `SimpleOrchestrator` | Task routing, budget management, knowledge injection |
| Workflow | `execute-workflow` method | Planning → Implementing → Testing phases |
| Inner Loop | Delegates to `loop` component | `loop/run-simple` for generate→validate→repair |
| Task Routing | `SimpleTaskRouter` | Maps task types to agent roles |
| Budget Management | `SimpleBudgetManager` | Token/cost/time tracking per workflow |
| Knowledge Injection | `SimpleKnowledgeCoordinator` | Injects zettels into agent context |

### What's Missing

1. **Operator (Meta-agent)** - No implementation yet
2. **Meta Loop** - No self-improvement mechanism
3. **Separation of Concerns** - Control Plane and Workflow are combined
4. **Outer Loop phases** - Only Planning, Implementing, Testing; missing Design, Verify, Review, Release, Observe

## Design Decision

For Sprint 1 (pipeline testability), we accept this combined architecture because:

1. It enables end-to-end testing of spec → plan → implement → test flow
2. All core components are wired together
3. Separation can happen incrementally without breaking changes

## Future Work

### Phase 2: Separate Workflow from Control Plane

Create `workflow` component:
- Move `execute-workflow` logic
- Define workflow phases explicitly
- Support phase rollback and resumption

Keep `orchestrator` as pure Control Plane:
- Task routing
- Budget enforcement
- Policy/knowledge coordination
- Escalation handling

### Phase 3: Add Operator Meta-Agent

Create `operator` component:
- Observe signals from workflow executions
- Propose process improvements
- Manage meta-loop
- Store patterns in knowledge base

### Phase 4: Meta Loop Implementation

Enable self-improvement:
- Track recurrent failure patterns
- Propose agent prompt changes
- Suggest new rules/policies
- Support shadow-mode evaluation

## Current Component Relationships

```
         ┌──────────────────────────────────────────────────────────────┐
         │                      orchestrator                            │
         │  ┌─────────────┐  ┌─────────────┐  ┌───────────────────┐    │
         │  │TaskRouter   │  │BudgetManager│  │KnowledgeCoordinator│   │
         │  └──────┬──────┘  └──────┬──────┘  └─────────┬─────────┘    │
         │         │                │                    │              │
         │  ┌──────┴────────────────┴────────────────────┴──────────┐  │
         │  │                 SimpleOrchestrator                     │  │
         │  │   execute-workflow: plan → implement → test            │  │
         │  └──────────────────────────────────────────────────────┘  │
         └──────────────────────────────────────────────────────────────┘
                    │              │              │
                    ▼              ▼              ▼
             ┌──────────┐   ┌──────────┐   ┌──────────┐
             │  agent   │   │   loop   │   │knowledge │
             │(planner, │   │(inner    │   │(zettels) │
             │ impl,    │   │ loop)    │   │          │
             │ tester)  │   │          │   │          │
             └──────────┘   └──────────┘   └──────────┘
```

## Testing Strategy

End-to-end test should verify:
1. Spec → Planning phase produces implementation tasks
2. Implementation tasks are executed with knowledge injection
3. Test tasks are generated and executed
4. Budget is tracked throughout
5. Artifacts are stored
6. Workflow status transitions correctly

This validates the pipeline even with the combined architecture.
