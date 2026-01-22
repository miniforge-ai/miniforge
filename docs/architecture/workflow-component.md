# Workflow Component Architecture

This document describes the architecture of the Workflow component, which makes EDN workflow
configurations live and executable at runtime.

## Overview

The Workflow component is the execution engine for configurable SDLC flows.

**Key responsibilities:**

- Load workflow configs from EDN files
- Validate configs against Malli schema
- Execute workflows by interpreting the DAG
- Track state during execution (current phase, budgets, metrics)
- Integrate with Policy component for gate evaluation
- Store/retrieve workflows via Heuristic component
- Enable Meta loop to propose and activate new workflows

This is the "interpreter" that makes workflow configs executable.

## Component Structure

```text
components/workflow/
├── src/ai/miniforge/workflow/
│   ├── interface.clj       # Public API
│   ├── core.clj            # Component orchestration
│   ├── loader.clj          # Load & validate configs
│   ├── executor.clj        # Execute workflow DAG
│   ├── state.clj           # Execution state management
│   └── validator.clj       # Schema & DAG validation
├── resources/
│   ├── schemas/
│   │   └── workflow.edn    # Malli schema
│   └── workflows/
│       ├── canonical-sdlc-v1.0.0.edn
│       └── lean-sdlc-v1.0.0.edn
└── test/
```

**Runtime configuration:**

```text
~/.miniforge/workflows/
└── active.edn              # Active workflow per task type
```

## Dependencies

- **policy**: Gate evaluation at phase boundaries
- **heuristic**: Workflow storage and versioning
- **schema**: Malli validation of configs
- **llm**: Agent invocation during phase execution

## Live Configuration

### Active Workflow Mapping

File: `~/.miniforge/workflows/active.edn`

```clojure
{:feature :canonical-sdlc-v1
 :bugfix :lean-sdlc-v1
 :refactor :canonical-sdlc-v1
 :component :canonical-sdlc-v1}
```

Maps task types to active workflow IDs.

### Hot-Reload Mechanism

Workflows can be updated without restarting miniforge:

1. Meta loop proposes new workflow config
2. Saves to `~/.miniforge/workflows/proposed-v1.1.0.edn`
3. Validates config (schema + DAG)
4. Updates `active.edn` to point to new version
5. Next task uses new workflow
6. Old executions continue with their original workflow

### Meta Loop Self-Update Cycle

The Meta loop can self-update workflow configs through a 9-step cycle:

1. **Observe**: Collect metrics from recent workflow executions
2. **Analyze**: Identify bottleneck phases (e.g., review takes 40% of time)
3. **Propose**: Generate new workflow config (e.g., reduce review rounds)
4. **Validate**: Check schema + DAG correctness
5. **Save**: Write to heuristic store with new version
6. **Experiment**: A/B test by routing 50% of tasks to new version
7. **Measure**: Compare metrics (success rate, time, cost)
8. **Decide**: If better, update active.edn to make it default
9. **Version**: Increment version (1.0.0 → 1.1.0)

**Example mutation:**

```clojure
;; Before (v1.0.0)
{:phase/id :review
 :phase/review-loop {:max-rounds 3}}

;; After (v1.1.0)
{:phase/id :review
 :phase/review-loop {:max-rounds 2}}  ; Reduced to save time
```

**Safety mechanisms:**

- Always validate before activating
- Keep old versions available for rollback
- Log all config changes for audit
- Gradual rollout (A/B test before full deployment)

## Integration with Existing System

### Release Executor

**File**: `components/release/src/ai/miniforge/release/executor.clj`

**Current**: Hardcodes the SDLC flow

**Integration**:

```clojure
(defn execute [spec]
  (let [workflow (workflow/get-active-workflow (:task-type spec))]
    (workflow/execute-workflow workflow spec)))
```

**Benefit**: Makes the entire flow configurable

### LLM Agents

**Files**: `components/llm/src/ai/miniforge/llm/agent.clj`

**Integration**: Agents are invoked by phases in the workflow config. Phase configs specify
which agent handles each phase.

### Policy Gates

**Files**: `components/policy/src/ai/miniforge/policy/gates.clj`

**Status**: Already built in Phase 1!

**Integration**: Wire into phase transitions:

```clojure
(defn advance-phase? [phase artifact]
  (policy/evaluate-gates artifact
                         (:phase/id phase)
                         (:phase/gates phase)))
```

### Heuristic Storage

**Files**: `components/heuristic/src/ai/miniforge/heuristic/core.clj`

**Status**: Already built in Phase 1!

**Integration**: Workflows are stored as versioned heuristics:

```clojure
(heuristic/save-heuristic :workflow-config "1.0.0" workflow-edn)
(heuristic/get-heuristic :workflow-config "1.0.0")
```

## Configuration Lifecycle

```text
┌─────────────────────────────────────────────────────────────────────┐
│ WORKFLOW CONFIGURATION LIFECYCLE                                     │
└─────────────────────────────────────────────────────────────────────┘

1. DESIGN TIME (Human/Meta Loop creates configs)
   ↓
   [EDN Config Files]
   - canonical-sdlc-v1.0.0.edn
   - lean-sdlc-v1.0.0.edn
   - proposed-v1.1.0.edn (Meta loop generated)
   ↓
2. VALIDATION (Schema + DAG correctness)
   ↓
   [Malli Schema Validation]
   - Type checking
   - Structure validation
   - DAG validation (no cycles, reachable nodes)
   ↓
3. STORAGE (Versioned in Heuristic component)
   ↓
   [Heuristic Storage: ~/.miniforge/heuristics/]
   - workflow-config/1.0.0.edn
   - workflow-config/1.1.0.edn
   ↓
4. ACTIVATION (Set active workflow per task type)
   ↓
   [Active Workflow Mapping: ~/.miniforge/workflows/active.edn]
   {:feature :canonical-sdlc-v1
    :bugfix :lean-sdlc-v1}
   ↓
5. RUNTIME (Load and execute)
   ↓
   [Workflow Executor]
   (workflow/execute-workflow
     (workflow/get-active-workflow :feature)
     user-spec)
```

## See Also

- `docs/specs/workflow-api.spec.edn` - Public API specification
- `docs/guides/workflow-implementation.md` - Implementation guide
- `docs/architecture/live-workflow-configuration.md` - Detailed configuration lifecycle
- `docs/specs/workflow-configuration.spec.edn` - Workflow EDN format spec
