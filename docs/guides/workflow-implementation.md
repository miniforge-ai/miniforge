# Workflow Component Implementation Guide

This guide provides implementation details for building the Workflow component.

## Core Namespaces

### Loader (`workflow.loader`)

**Purpose**: Load and validate workflow configs

**Functions:**

```clojure
(defn load-from-file [path]
  (-> path slurp edn/read-string validate-workflow))

(defn load-from-heuristic [workflow-id version]
  (heuristic/get-heuristic workflow-id version))

(defn validate-workflow [config]
  (let [schema (load-schema)]
    (if (m/validate schema config)
      config
      (throw (ex-info "Invalid workflow config"
                      {:errors (m/explain schema config)})))))
```

### Executor (`workflow.executor`)

**Purpose**: Execute workflow DAG with state management

**Functions:**

```clojure
(defn execute [workflow input opts]
  (let [state (init-state workflow input)]
    (loop [current-phase (first-phase workflow)
           state state]
      (if (terminal-phase? current-phase)
        (finalize-execution state)
        (let [result (execute-phase current-phase state)
              next-phase (select-next-phase result)]
          (recur next-phase (update-state state result)))))))

(defn execute-phase [phase state]
  ;; 1. Check budget
  ;; 2. Run inner loop (generate → validate → repair)
  ;; 3. Run review loop (if configured)
  ;; 4. Evaluate gates
  ;; 5. Collect metrics
  ;; 6. Return result
  {:phase/id (:phase/id phase)
   :phase/status :passed
   :phase/iterations 3
   :phase/metrics {...}})

(defn select-next-phase [result]
  ;; Interpret phase/next transitions
  ;; Support conditional branching
  ;; Handle parallel phases (future)
  (first (filter #(gates-pass? % result)
                 (:phase/next result))))
```

### State Manager (`workflow.state`)

**Purpose**: Manage workflow execution state

**Functions:**

```clojure
(defn init-state [workflow input]
  {:execution/id (random-uuid)
   :execution/workflow-id (:workflow/id workflow)
   :execution/start-time (Instant/now)
   :execution/current-phase nil
   :execution/phases []
   :execution/artifact input
   :execution/budget-used {:tokens 0 :time 0 :iterations 0}
   :execution/metrics []})

(defn update-state [state phase-result]
  (-> state
      (update :execution/phases conj phase-result)
      (update :execution/budget-used merge-budgets (:budget-used phase-result))
      (update :execution/metrics conj (:metrics phase-result))
      (assoc :execution/artifact (:artifact phase-result))))

(defn finalize-execution [state]
  (assoc state
         :execution/end-time (Instant/now)
         :execution/status :completed
         :execution/total-time (calculate-duration state)
         :execution/summary (summarize-metrics state)))
```

### Validator (`workflow.validator`)

**Purpose**: Validate workflow configs and DAG correctness

**Functions:**

```clojure
(defn validate-dag [workflow]
  ;; Check for cycles
  ;; Verify all phase/next targets exist
  ;; Ensure single start node
  ;; Ensure reachability of all phases
  (when-let [errors (check-dag-structure workflow)]
    (throw (ex-info "Invalid DAG" {:errors errors}))))

(defn validate-gates [phase]
  ;; Ensure all gate types are known
  ;; Verify gate configs are valid
  (policy/validate-gate-configs (:phase/gates phase)))

(defn validate-budgets [workflow]
  ;; Check phase budgets sum <= total budget
  ;; Ensure all budgets are positive
  (when (> (sum-phase-budgets workflow)
          (get-in workflow [:workflow/config :max-total-tokens]))
    (throw (ex-info "Budget overflow" {...}))))
```

## Integration Examples

### Policy Component

Evaluate gates at phase boundaries:

```clojure
(defn evaluate-phase-gates [phase artifact]
  (policy/evaluate artifact
                   (:phase/id phase)
                   (:phase/gates phase)))
```

### Heuristic Component

Store/retrieve workflows as versioned heuristics:

```clojure
(defn save-workflow-as-heuristic [workflow]
  (heuristic/save-heuristic
    (:workflow/id workflow)
    (:workflow/version workflow)
    workflow))
```

### Loop Component

Execute inner loops within phases:

```clojure
(defn run-inner-loop [phase artifact agent]
  (loop-engine/execute
    {:max-iterations (get-in phase [:phase/inner-loop :max-iterations])
     :validator (get-validator phase)
     :repairer (get-repairer phase agent)
     :artifact artifact}))
```

### LLM Component

Invoke agents for phase actions:

```clojure
(defn invoke-agent [agent action artifact context]
  (llm/chat agent
            (build-prompt action artifact)
            {:context context}))
```

## Testing Strategy

### Unit Tests

- Test workflow loading from EDN
- Test schema validation (valid and invalid configs)
- Test DAG validation (cycles, unreachable nodes)
- Test phase execution (with mocked agents)
- Test gate evaluation integration
- Test budget tracking
- Test state transitions

### Integration Tests

- Test full workflow execution with real Policy component
- Test workflow storage/retrieval via Heuristic component
- Test hot-reload of workflow configs
- Test Meta loop updating active workflow

### End-to-End Tests

- Execute canonical-sdlc-v1 on a simple spec
- Execute lean-sdlc-v1 on same spec
- Compare metrics between executions
- Verify Meta loop can propose and activate new workflow

## Implementation Plan

### Phase 1: Loader and Validator

**Tasks:**

1. Create `components/workflow/deps.edn`
2. Implement `workflow.loader` (load from file/heuristic)
3. Implement `workflow.validator` (schema + DAG validation)
4. Add tests for loading and validation
5. Wire into heuristic component for storage

### Phase 2: Executor Core

**Tasks:**

1. Implement `workflow.state` (execution state management)
2. Implement `workflow.executor` (DAG interpretation)
3. Implement phase execution (stub agents initially)
4. Add tests for execution flow
5. Track budgets and metrics

### Phase 3: Integration

**Tasks:**

1. Wire executor to Policy component (gate evaluation)
2. Wire executor to LLM component (agent invocation)
3. Wire executor to Loop component (inner loops)
4. Create `active.edn` config file
5. Implement `get-active-workflow` / `set-active-workflow`

### Phase 4: Meta Loop Integration

**Tasks:**

1. Enable Meta loop to propose new workflow configs
2. Implement A/B testing framework
3. Implement comparison and promotion logic
4. Add hot-reload support
5. Test self-updating workflows

## Success Criteria

- ✓ Can load workflow configs from EDN files
- ✓ Validates configs against Malli schema
- ✓ Detects invalid DAGs (cycles, unreachable nodes)
- ✓ Executes workflows by interpreting the DAG
- ✓ Tracks execution state (phases, budgets, metrics)
- ✓ Integrates with Policy component for gates
- ✓ Stores workflows via Heuristic component
- ✓ Supports active workflow per task type
- ✓ Meta loop can propose and activate new workflows
- ✓ Hot-reload works without restart
- ✓ Can compare workflow executions
- ✓ All tests pass
- ✓ Dogfood: Use workflow component to execute Phase 3 tasks

## See Also

- `docs/specs/workflow-api.spec.edn` - Public API specification
- `docs/architecture/workflow-component.md` - Component architecture
- `docs/specs/workflow-configuration.spec.edn` - Workflow EDN format
- `components/workflow/resources/schemas/workflow.edn` - Malli schema
