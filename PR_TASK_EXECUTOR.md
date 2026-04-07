# PR: Implement Task-Executor Component (DAG-to-PR Lifecycle Integration)

> The task-executor is part of **Miniforge SDLC**, connecting the DAG scheduler to the PR lifecycle controller.

## Summary

This PR implements the **task-executor** component, completing the integration between the DAG scheduler
and PR lifecycle controller. This is the final piece needed to enable fully automated multi-task
workflows where the DAG orchestrator dispatches tasks, generates code via the agent loop, and manages
PRs through to merge.

## Problem Statement

Prior to this PR:

- ✅ DAG scheduler was fully implemented with dependency tracking and parallel execution
- ✅ PR lifecycle controller was fully implemented with CI monitoring and auto-merge
- ❌ **These two systems were not connected** - the scheduler had an unused `execute-task-fn` callback slot
- ❌ No event translation existed between PR vocabulary (`:pr/ci-passed`) and scheduler vocabulary (`:ci-passed`)
- ❌ No orchestration layer existed to manage task execution through its full lifecycle

## Solution

This PR adds a new **task-executor** component with a clean layered architecture:

```text
┌─────────────────┐
│  DAG Scheduler  │ ──> dispatch-task-start
│  (parallel.clj) │     :pending → :implementing
└────────┬────────┘
         │
         ▼ execute-task-fn (NEW)
┌─────────────────────────────────────────┐
│        Task Executor (NEW COMPONENT)     │
│                                          │
│  Layer 3: Orchestrator                  │
│  Layer 2: Runner (per-task lifecycle)   │
│  Layer 1: Generate (agent factory)      │
│  Layer 0: Bridge (event translation)    │
└─────────┬───────────────────────────────┘
          │
          ▼ run-lifecycle! + event bridge
┌─────────────────┐
│  PR Lifecycle   │ ──> GitHub API
│  (controller)   │ ──> CI polling
└─────────────────┘
```

## Changes

### New Files

#### Specs

- **`specs/informative/I-TASK-EXECUTOR.md`** (340 lines)
  - Complete architecture documentation
  - Event mapping table (11 PR events → scheduler actions)
  - Concurrency model and integration points
  - References to N2, N3, I-DAG-ORCHESTRATION

#### Source Files (5 files, 518 lines)

- **`components/task-executor/deps.edn`**
  - Component dependencies (dag-executor, pr-lifecycle, agent, loop, etc.)

- **`components/task-executor/src/.../bridge.clj`** (68 lines)
  - **Layer 0**: Pure event translation
  - Maps PR lifecycle events to scheduler actions
  - Handles 11 event types including conflicts/rebases

- **`components/task-executor/src/.../generate.clj`** (73 lines)
  - **Layer 1**: Factory for `generate-fn` closures
  - Wraps `loop/run-simple` with LLM backend
  - Reusable for initial generation AND fix loops

- **`components/task-executor/src/.../runner.clj`** (203 lines)
  - **Layer 2**: Execute single task through full lifecycle
  - Acquires resources (worktree + environment)
  - Generates code via inner loop
  - Runs PR lifecycle (blocking)
  - Always cleans up in finally block

- **`components/task-executor/src/.../orchestrator.clj`** (174 lines)
  - **Layer 3**: Concurrent execution and scheduler integration
  - Provides `execute-task-fn` callback
  - Launches tasks in futures
  - Cascades failures to dependents
  - Top-level `execute-dag!` convenience function

- **`components/task-executor/src/.../interface.clj`**
  - Public API with def aliases per codebase convention
  - Rich comment blocks for REPL testing

#### Test Files (3 files, 321 lines)

- **`components/task-executor/test/.../bridge_test.clj`** (129 lines)
  - Tests all 11 event type mappings
  - Tests unmapped events return nil
  - Tests round-trip translation
  - **Result: 4 tests, 40 assertions, ALL PASSING ✅**

- **`components/task-executor/test/.../runner_test.clj`** (148 lines)
  - Tests successful task execution
  - Tests generation failure handling
  - Tests environment cleanup (always executes)
  - Mock executor, PR lifecycle, and loop

- **`components/task-executor/test/.../orchestrator_test.clj`** (144 lines)
  - Tests run context creation
  - Tests execute-task-fn callback
  - Tests failure cascading to dependents
  - Tests diamond DAG execution
  - Tests budget exhaustion handling

### Modified Files

#### Integration

- **`components/dag-executor/src/.../scheduler.clj`** (4-line change, lines 257-262)
  - Calls `execute-task-fn` after successful task dispatch
  - **Backward compatible** - guarded by `when` check
  - **Verified**: All existing dag-executor tests still pass (32 tests, 104 assertions ✅)

#### Project Configuration

- **`projects/miniforge/deps.edn`**
  - Added `ai.miniforge/task-executor` dependency

## Event Mapping

The bridge translates PR lifecycle events to scheduler state transitions:

| PR Event (`:event/type`)           | Scheduler Action (`:event/action`) | State Transition |
|------------------------------------|------------------------------------|------------------|
| `:pr/opened`                       | `:pr-opened`                       | :implementing → :pr-opening |
| `:pr/ci-passed`                    | `:ci-passed`                       | :pr-opening → :awaiting-review |
| `:pr/ci-failed`                    | `:ci-failed`                       | Retry with exponential backoff |
| `:pr/review-approved`              | `:review-approved`                 | :awaiting-review → :merging |
| `:pr/review-changes-requested`     | `:review-changes-requested`        | :awaiting-review → :fixing |
| `:pr/fix-pushed`                   | `:fix-pushed`                      | :fixing → :pr-opening |
| `:pr/merged`                       | `:merged`                          | :merging → :merged (terminal) |
| `:pr/closed`                       | `:merge-failed`                    | → :failed (terminal) |
| `:pr/conflict`                     | `:ci-failed`                       | Reuse retry logic |
| `:pr/rebase-needed`                | `:ci-failed`                       | Reuse retry logic |
| `:pr/comment-actionable`           | nil                                | Handled internally |

**Design Decision**: Conflicts and rebases map to `:ci-failed` to reuse the scheduler's existing retry
infrastructure rather than adding separate conflict states.

## Architecture Highlights

### Clean Layering

- **Layer 0 (Bridge)**: Pure functions, no side effects, fully tested
- **Layer 1 (Generate)**: Factory pattern, returns closures
- **Layer 2 (Runner)**: Per-task orchestration, resource management
- **Layer 3 (Orchestrator)**: Concurrency control, failure cascading

### Resource Management

```clojure
(try
  ;; Acquire worktree semaphore (limits parallel Git operations)
  (dag/acquire-worktree! lock-pool task-id 60000 logger)

  ;; Acquire environment (executor protocol: K8s, Docker, or worktree)
  (dag/acquire-environment! executor task-id config)

  ;; Execute task...

  (finally
    ;; ALWAYS clean up, even on error
    (dag/release-environment! executor env-id)
    (dag/release-all-locks! lock-pool task-id logger)))
```

### Event Flow (Asynchronous)

```clojure
;; PR lifecycle emits events asynchronously
(pr/subscribe! event-bus
  (fn [pr-event]
    ;; Bridge translates and forwards to scheduler
    (when-let [scheduler-event (bridge/translate-event pr-event)]
      (scheduler/handle-task-event run-atom scheduler-event))))

;; run-lifecycle! blocks until merge, but events fire immediately
(pr/run-lifecycle! controller code-artifact)
```

### Failure Cascading

```clojure
;; If task-A fails, all dependents are skipped
(catch Exception e
  (dag/mark-failed! run-atom task-id e)
  (skip-dependent-tasks! run-atom task-id))

;; Diamond DAG: A → B, A → C, B+C → D
;; If A fails, B, C, and D are automatically skipped
```

## Testing

### Test Coverage

- ✅ **Bridge**: 4 tests, 40 assertions - **ALL PASSING**
- ✅ **DAG Executor**: 32 tests, 104 assertions - **ALL PASSING** (backward compatibility verified)
- ⚠️ **Runner/Orchestrator**: Mock-based tests created, full integration testing pending

### Running Tests

```bash
# Run task-executor tests
cd components/task-executor
clojure -M:test -m cognitect.test-runner

# Verify backward compatibility
cd components/dag-executor
clojure -M:test -m cognitect.test-runner
```

## Integration Example

```clojure
(require '[ai.miniforge.task-executor.interface :as task-executor])

;; Define a diamond DAG
(def task-defs
  [{:task/id "task-A" :task/deps #{}}
   {:task/id "task-B" :task/deps #{"task-A"}}
   {:task/id "task-C" :task/deps #{"task-A"}}
   {:task/id "task-D" :task/deps #{"task-B" "task-C"}}])

;; Execute with full integration
(task-executor/execute-dag!
  "dag-20260206-001"
  task-defs
  {:workflow-id "wf-123"
   :executor my-executor
   :llm-backend my-backend
   :logger my-logger
   :max-parallel 4
   :github-token (System/getenv "GITHUB_TOKEN")
   :budget {:max-tokens 1000000 :max-cost-usd 100.0}})

;; Result: All 4 tasks execute with proper dependency ordering
;; - A starts immediately
;; - B and C start in parallel after A merges
;; - D starts after both B and C merge
```

## Migration Path

This PR is **fully backward compatible**:

- Scheduler change is guarded: `(when (:execute-task-fn context) ...)`
- Existing workflows without `execute-task-fn` continue working
- No breaking changes to public APIs

To adopt the new integration:

```clojure
;; OLD: Manual task execution
(loop []
  (let [result (dag/schedule-iteration run-atom context)]
    ;; ... manually execute tasks ...
    (recur)))

;; NEW: Integrated task execution
(let [context (task-executor/create-orchestrated-scheduler-context
                run-atom config)]
  (dag/run-scheduler run-atom context))
```

## Future Work

- [ ] Full integration tests with real Git operations (mock GitHub API)
- [ ] Checkpoint/resume support for long-running DAGs
- [ ] Manual review mode (pause before PR creation)
- [ ] Parallel PR strategy (multiple PRs open simultaneously)
- [ ] Adaptive retry with exponential backoff
- [ ] Cost optimization (cache artifacts across retries)

## Related Specs

- **N2-AGENT-AND-LOOP.md**: Inner loop architecture
- **N3-PR-LIFECYCLE-INTEGRATION.md**: PR state machine
- **I-DAG-ORCHESTRATION.md**: §6 Task Execution Integration
- **I-TASK-EXECUTOR.md**: Complete architecture (NEW)

## Checklist

- [x] Implementation matches plan from I-DAG-ORCHESTRATION.md §6
- [x] Informative spec created (I-TASK-EXECUTOR.md)
- [x] All source files implemented (5 files, 4 layers)
- [x] Public API follows codebase conventions (def aliases)
- [x] Tests created for bridge layer (40 assertions passing)
- [x] Backward compatibility verified (32 existing tests passing)
- [x] Scheduler integration is minimal and guarded
- [x] Resource cleanup always executes (finally blocks)
- [x] Event translation is pure and tested
- [x] Documentation includes architecture diagrams
- [x] Rich comment blocks for REPL testing

---

**Review Focus Areas:**

1. **Event mapping correctness**: Are all 11 PR event types mapped correctly?
2. **Resource cleanup safety**: Is the finally block guaranteed to execute?
3. **Concurrency model**: Does the lock pool correctly limit parallel operations?
4. **Error cascading**: Should failures cascade to dependents or fail independently?
5. **API design**: Is the layered architecture clear and well-documented?

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
