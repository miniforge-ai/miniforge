<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# I-TASK-EXECUTOR: DAG-to-PR Lifecycle Integration

**Status:** Informative
**Created:** 2026-02-06
**Related specs:** N2-AGENT-AND-LOOP.md, N3-PR-LIFECYCLE-INTEGRATION.md, I-DAG-ORCHESTRATION.md

## Purpose

The **task-executor** component bridges the DAG scheduler (`dag-executor`) and PR lifecycle controller
(`pr-lifecycle`), providing the missing integration layer that connects task dispatch to code generation
and merge automation.

Prior to this component:

- The scheduler had an unused `execute-task-fn` callback slot (scheduler.clj:200)
- The PR lifecycle's `run-lifecycle!` was never invoked from DAG context
- No event translation existed between PR vocabulary (`:pr/ci-passed`) and scheduler vocabulary (`:ci-passed`)

This component provides the integration architecture described in I-DAG-ORCHESTRATION.md §6.

## Architecture

### Data Flow

```text
┌─────────────────┐
│  DAG Scheduler  │
│  (parallel.clj) │
└────────┬────────┘
         │ schedule-iteration
         │ dispatch-task-start
         ├──> :pending → :implementing
         │
         ▼
┌─────────────────────────────────────────┐
│        Task Executor (this component)    │
│                                          │
│  ┌──────────────┐    ┌──────────────┐  │
│  │ Orchestrator │───>│   Runner     │  │
│  │ (execute-fn) │    │ (per task)   │  │
│  └──────────────┘    └──────┬───────┘  │
│                              │           │
│  ┌──────────────┐    ┌──────▼───────┐  │
│  │   Bridge     │<───│  Generate    │  │
│  │ (events)     │    │  (loop)      │  │
│  └──────┬───────┘    └──────────────┘  │
│         │                                │
└─────────┼────────────────────────────────┘
          │ translate events
          ▼
┌─────────────────┐
│  PR Lifecycle   │
│  (controller)   │
│                 │
│  run-lifecycle! │ ──> GitHub API
│  (blocking)     │ ──> CI polling
│                 │ ──> Auto-merge
└─────────────────┘
```

### Component Layers (Bottom-Up)

**Layer 0: Bridge** (`bridge.clj`)
Pure event translation between vocabularies. Maps PR lifecycle events (`:pr/ci-passed`) to scheduler actions (`:ci-passed`).

**Layer 1: Generate** (`generate.clj`)
Factory for `generate-fn` closures that wrap agent + inner loop. Same closure used for initial generation AND fix loops.

**Layer 2: Runner** (`runner.clj`)
Executes a single task through its full lifecycle:

1. Acquire environment (semaphore + worktree)
2. Generate code artifact (inner loop)
3. Create PR lifecycle controller
4. Run blocking PR lifecycle (open → CI → review → merge)
5. Forward events back to scheduler via bridge
6. Clean up environment

**Layer 3: Orchestrator** (`orchestrator.clj`)
Provides `execute-task-fn` callback and manages concurrent task futures.

## Event Translation

The bridge maps PR lifecycle events to scheduler state machine actions:

| PR Event (`:event/type`)           | Scheduler Action (`:event/action`) | Notes |
|------------------------------------|------------------------------------|-------|
| `:pr/opened`                       | `:pr-opened`                       | State: :implementing → :pr-opening |
| `:pr/ci-passed`                    | `:ci-passed`                       | State: :pr-opening → :awaiting-review |
| `:pr/ci-failed`                    | `:ci-failed`                       | Retry logic in scheduler |
| `:pr/review-approved`              | `:review-approved`                 | State: :awaiting-review → :merging |
| `:pr/review-changes-requested`     | `:review-changes-requested`        | State: :awaiting-review → :fixing |
| `:pr/fix-pushed`                   | `:fix-pushed`                      | State: :fixing → :pr-opening |
| `:pr/merged`                       | `:merged`                          | State: :merging → :merged |
| `:pr/closed`                       | `:merge-failed`                    | Terminal failure |
| `:pr/conflict`                     | `:ci-failed`                       | Treat as retriable failure |
| `:pr/rebase-needed`                | `:ci-failed`                       | Treat as retriable failure |
| `:pr/comment-actionable`           | nil                                | Handled internally by PR controller |

**Rationale for conflict/rebase → `:ci-failed`:**
The scheduler's state machine (scheduler.clj:130-185) has retry logic for CI failures but not for
separate conflict states. Mapping conflicts to CI failures reuses existing retry infrastructure.

## Concurrency Model

### Task Execution

Each task executes in its own future (managed by orchestrator):

```clojure
(future
  (try
    (runner/execute-task task-id task run-context)
    (catch Exception e
      (dag/mark-failed! run-atom task-id e)
      (scheduler/skip-dependent-tasks run-atom task-id))))
```

### Resource Acquisition

Per-task sequence (runner.clj):

1. **Worktree semaphore** (from parallel.clj): `(dag/acquire-worktree! run-atom task-id)`
   - Limits parallel Git operations
   - Released in finally block

2. **Environment** (from executor.clj protocol): `(dag/acquire-environment! executor task-id)`
   - Returns `{:env-id :env-123, :session-id :sess-456, ...}`
   - Released in finally block

3. **PR lifecycle controller**: Created per-task, no global limit
   - Blocks on `run-lifecycle!` until merge or failure
   - Event bus cleaned up automatically

### Event Flow

PR lifecycle events flow back to scheduler via bridge callback:

```clojure
(pr/subscribe! event-bus
  (fn [pr-event]
    (when-let [scheduler-event (bridge/translate-event pr-event)]
      (scheduler/handle-task-event run-atom scheduler-event))))
```

This is **asynchronous**: `run-lifecycle!` blocks the task future but events fire immediately.

## Integration Points

### With DAG Executor

**Scheduler modification** (scheduler.clj:257-259):

```clojure
;; After dispatching task, invoke execute-task-fn if provided
(when (and (result/ok? result) (:execute-task-fn context))
  ((:execute-task-fn context) task-id context))
```

Backward compatible: guarded by `when`. Existing tests pass unchanged.

**Context requirements:**

```clojure
{:execute-task-fn (fn [task-id context] ...) ; provided by orchestrator
 :run-atom run-atom                           ; shared state
 :max-parallel 4                              ; concurrency limit
 :config {...}}                               ; full config
```

### With PR Lifecycle

**Controller creation** (runner.clj):

```clojure
(let [event-bus (pr/create-event-bus)
      controller (pr/create-controller
                   {:event-bus event-bus
                    :workflow-id workflow-id
                    :generate-fn (generate/create-generate-fn llm-backend ...)
                    :task-description (:description task)
                    :branch-name (str "task-" task-id)
                    :logger logger})]
  (pr/subscribe! event-bus translate-and-forward)
  (pr/run-lifecycle! controller))
```

**Generate function reuse:**
The same `generate-fn` closure is used for:

- Initial code generation (runner.clj initial loop)
- Fix loops (PR controller's `:generate-fn` option)

This ensures consistency: both use `loop/run-simple` with the same agent backend.

### With Agent & Loop

**Generate factory** (generate.clj):

```clojure
(defn create-generate-fn [llm-backend & {:keys [logger event-stream workflow-id max-iterations]}]
  (fn [task context]
    (let [result (loop/run-simple
                   llm-backend
                   {:prompt (format-task-prompt task context)
                    :max-iterations (or max-iterations 10)
                    :logger logger
                    :event-stream event-stream
                    :workflow-id workflow-id})]
      {:artifact (:code-artifact result)
       :tokens (:total-tokens result)})))
```

Wraps `loop/run-simple` (loop/interface.clj:88) per N2-AGENT-AND-LOOP.md.

## Configuration

### Top-Level (orchestrator.clj)

```clojure
{:workflow-id "dag-run-20260206-001"
 :max-parallel 4
 :max-iterations 10
 :llm-backend llm-backend-instance
 :executor executor-instance         ; implements IEnvironmentExecutor
 :github-token "ghp_..."
 :logger logger-instance
 :budget {:max-tokens 1000000
          :max-cost-usd 100.0}}
```

### Per-Task Context (runner.clj)

```clojure
{:task-id "task-123"
 :task {:description "Implement feature X"
        :dependencies ["task-100"]
        :files ["src/core.clj"]
        ...}
 :run-context {...}                  ; shared across all tasks
 :env-record {:env-id :env-123       ; from acquire-environment!
              :session-id :sess-456
              :worktree-path "/tmp/wt-123"}}
```

## Error Handling

### Task Execution Errors

Caught in orchestrator future:

```clojure
(catch Exception e
  (log/error logger {:event :task-execution-error
                     :task-id task-id
                     :error (ex-message e)})
  (dag/mark-failed! run-atom task-id e)
  (scheduler/skip-dependent-tasks run-atom task-id))
```

Failures cascade to dependent tasks via scheduler's skip logic.

### Environment Cleanup

Always executed in runner's finally block:

```clojure
(finally
  (when env-record
    (dag/release-environment! executor (:env-id env-record))
    (dag/release-all-locks! run-atom task-id)))
```

### Budget Exhaustion

Checked by scheduler on each iteration. When budget exceeded:

1. Scheduler stops dispatching new tasks
2. In-flight tasks complete normally
3. Run transitions to `:budget-exceeded` status

## Metrics & Observability

### Per-Task Metrics (runner.clj)

Accumulated during task execution:

```clojure
{:tokens-used 45000
 :cost-usd 2.34
 :iterations 3
 :pr-url "https://github.com/org/repo/pull/123"
 :ci-runs 2
 :review-cycles 1
 :time-to-merge-ms 450000}
```

Updated via `dag/update-metrics! run-atom task-id metrics`.

### Event Stream

All components emit structured events:

```clojure
{:event/type :task-executor/task-started
 :task-id "task-123"
 :timestamp #inst "2026-02-06T10:30:00Z"}

{:event/type :task-executor/environment-acquired
 :task-id "task-123"
 :env-id :env-456}

{:event/type :task-executor/pr-lifecycle-started
 :task-id "task-123"
 :branch-name "task-123"}

{:event/type :task-executor/task-completed
 :task-id "task-123"
 :metrics {...}}
```

## Testing Strategy

### Unit Tests

**bridge_test.clj:**

- All 11 PR event types translate correctly
- Unmapped types return nil
- Round-trip via `create-scheduler-event`

**runner_test.clj:**

- Single task with mocked executor, generate-fn, PR lifecycle
- CI failure → fix loop → merge
- Execution error → mark-failed! + skip dependents
- Environment cleanup in finally

**orchestrator_test.clj:**

- Diamond DAG (A → B, A → C, B+C → D) with mocks
- Concurrent dispatch respects max-parallel
- Task failure cascades to dependents
- Budget exhaustion pauses run

### Integration Tests

**Full DAG run** (future work):

1. Real Git repository (temporary)
2. Mock GitHub API responses
3. Mock LLM backend (canned responses)
4. Verify: 3-task linear DAG completes with all :merged

## Future Enhancements

1. **Checkpoint/Resume:** Serialize run-atom state to disk, resume failed runs
2. **Manual Review Mode:** Pause before PR creation, show diff to user
3. **Parallel PR Strategy:** Open multiple PRs simultaneously, merge in topological order
4. **Adaptive Retry:** Exponential backoff for CI failures, different strategies for conflicts
5. **Cost Optimization:** Cache generated artifacts, reuse across retries

## References

- **N2-AGENT-AND-LOOP.md:** Inner loop architecture (agent + tool execution)
- **N3-PR-LIFECYCLE-INTEGRATION.md:** PR state machine and event bus
- **I-DAG-ORCHESTRATION.md:** §6 Task Execution Integration (architecture overview)
- **scheduler.clj:130-185:** State machine transitions
- **events.clj:13-25:** PR event type definitions
- **controller.clj:341-479:** `run-lifecycle!` blocking loop
- **parallel.clj:** Semaphore-based worktree management
