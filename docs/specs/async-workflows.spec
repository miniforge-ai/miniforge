# miniforge.ai — Asynchronous Workflows Specification

**Version:** 0.1.0  
**Status:** Draft  
**Date:** 2026-01-18  

---

## 1. Overview

### 1.1 Purpose

miniforge.ai supports **concurrent, controllable workflows** where:

- Multiple outer loops run simultaneously for different features/specs
- The Operator agent can pause, restart, stop, and spawn workflows
- The Meta loop monitors and responds to signals across all active workflows
- Customers can scale to many parallel development streams

This is not batch processing—it's **orchestrated concurrency** with observability and control.

### 1.2 Design Principles

1. **Workflows are independent**: Each workflow has isolated state; failures don't cascade
2. **Operator has authority**: The Operator agent (or human) can control any workflow
3. **Resources are budgeted**: Concurrency is bounded by cost, token, and compute limits
4. **State is recoverable**: Workflows can resume from any checkpoint after pause/failure
5. **Visibility is universal**: All workflows are observable from a single control surface

---

## 2. Workflow Model

### 2.1 Workflow Schema

```clojure
{:workflow/id          uuid
 :workflow/name        string          ; human-readable identifier
 :workflow/spec-id     uuid            ; originating specification
 :workflow/status      keyword         ; see Status State Machine
 :workflow/phase       keyword         ; current outer loop phase
 :workflow/priority    integer         ; scheduling priority (1-10, higher = more urgent)
 
 ;; Lifecycle
 :workflow/created-at  inst
 :workflow/created-by  string          ; user or agent that initiated
 :workflow/started-at  inst
 :workflow/paused-at   inst            ; if paused
 :workflow/completed-at inst           ; if completed
 
 ;; State & Checkpoints
 :workflow/checkpoint  map             ; serialized state for resume
 :workflow/artifacts   [uuid]          ; artifacts produced so far
 :workflow/tasks       [uuid]          ; tasks created
 
 ;; Resources
 :workflow/budget      {:tokens long :cost-usd double :time-ms long}
 :workflow/consumed    {:tokens long :cost-usd double :time-ms long}
 
 ;; Relationships
 :workflow/parent-id   uuid            ; if spawned by another workflow
 :workflow/children    [uuid]          ; spawned sub-workflows
 
 ;; Control
 :workflow/owner       string          ; who can control this workflow
 :workflow/tags        #{keyword}}     ; for filtering and grouping
```

### 2.2 Status State Machine

```
                         ┌─────────────────────────────────────┐
                         │                                     │
                         ▼                                     │
┌──────────┐  start  ┌─────────┐  pause  ┌──────────┐  resume │
│ PENDING  │ ──────► │ RUNNING │ ──────► │  PAUSED  │ ────────┘
└──────────┘         └────┬────┘         └────┬─────┘
                          │                   │
                          │ complete          │ stop
                          ▼                   ▼
                   ┌─────────────┐     ┌───────────┐
                   │  COMPLETED  │     │  STOPPED  │
                   └─────────────┘     └───────────┘
                          ▲                   ▲
                          │                   │
                   ┌──────┴───────────────────┘
                   │
              ┌────┴────┐
              │ FAILED  │ ◄── (from RUNNING on unrecoverable error)
              └─────────┘
```

### 2.3 Status Definitions

| Status      | Meaning                                           | Transitions To           |
|-------------|---------------------------------------------------|--------------------------|
| `PENDING`   | Created but not yet started                       | RUNNING, STOPPED         |
| `RUNNING`   | Actively executing                                | PAUSED, COMPLETED, FAILED|
| `PAUSED`    | Suspended, state preserved, can resume            | RUNNING, STOPPED         |
| `COMPLETED` | Successfully finished                             | (terminal)               |
| `STOPPED`   | Manually terminated                               | (terminal)               |
| `FAILED`    | Terminated due to unrecoverable error             | (terminal)               |

---

## 3. Workflow Control API

### 3.1 Control Protocol

```clojure
(defprotocol WorkflowController
  ;; Lifecycle
  (create-workflow [this spec config]
    "Create a new workflow from specification, returns workflow-id")
  
  (start-workflow [this workflow-id]
    "Begin execution of a pending workflow")
  
  (pause-workflow [this workflow-id reason]
    "Suspend a running workflow, preserving state")
  
  (resume-workflow [this workflow-id]
    "Resume a paused workflow from checkpoint")
  
  (stop-workflow [this workflow-id reason]
    "Terminate a workflow (cannot be resumed)")
  
  (restart-workflow [this workflow-id]
    "Stop and start a fresh instance from the same spec")
  
  ;; Query
  (get-workflow [this workflow-id]
    "Get full workflow state")
  
  (list-workflows [this criteria]
    "List workflows matching criteria")
  
  ;; Sub-workflows
  (spawn-workflow [this parent-id spec config]
    "Create a child workflow linked to parent"))
```

### 3.2 Control Commands

Commands can originate from:
- Human operators (via CLI/API)
- Operator agent (programmatic)
- Meta loop (automated response to signals)

```clojure
{:command/id        uuid
 :command/type      keyword          ; :start, :pause, :resume, :stop, :restart, :spawn
 :command/target    uuid             ; workflow-id
 :command/issued-by string           ; user or agent identifier
 :command/issued-at inst
 :command/reason    string           ; why this command was issued
 :command/params    map}             ; command-specific parameters
```

### 3.3 Command Authorization

| Command  | Allowed Issuers                              | Requires Approval |
|----------|----------------------------------------------|-------------------|
| create   | Human, Operator agent                        | No                |
| start    | Human, Operator agent                        | No                |
| pause    | Human, Operator agent, Meta loop             | No                |
| resume   | Human, Operator agent                        | No (by default)   |
| stop     | Human, Operator agent                        | Configurable      |
| restart  | Human, Operator agent                        | Configurable      |
| spawn    | Human, Operator agent, any agent (if allowed)| No                |

---

## 4. Checkpointing & Recovery

### 4.1 Checkpoint Schema

```clojure
{:checkpoint/id           uuid
 :checkpoint/workflow-id  uuid
 :checkpoint/created-at   inst
 :checkpoint/phase        keyword        ; outer loop phase
 :checkpoint/task-id      uuid           ; current/last task
 :checkpoint/task-status  keyword
 
 ;; Serialized state
 :checkpoint/agent-memory map            ; agent context at checkpoint
 :checkpoint/pending-tasks [uuid]        ; tasks not yet completed
 :checkpoint/artifacts    [uuid]         ; artifacts produced
 
 ;; Resource tracking
 :checkpoint/consumed     {:tokens long :cost-usd double :time-ms long}
 
 ;; For debugging
 :checkpoint/trigger      keyword        ; :pause, :phase-complete, :periodic, :failure
 :checkpoint/notes        string}
```

### 4.2 Checkpoint Triggers

| Trigger          | When                                    | Purpose                    |
|------------------|-----------------------------------------|----------------------------|
| Phase complete   | After each outer loop phase             | Natural recovery point     |
| Pause command    | When workflow is paused                 | Resume from exact state    |
| Periodic         | Every N minutes of execution            | Limit replay on failure    |
| Pre-risky        | Before high-risk operations (deploy)    | Rollback point             |
| Failure          | On recoverable error                    | Debug + manual resume      |

### 4.3 Resume Semantics

On resume:
1. Load checkpoint state
2. Restore agent memory/context
3. Resume from last incomplete task
4. Re-validate any in-progress artifacts (they may be stale)

```clojure
(defn resume-workflow [workflow-id]
  (let [checkpoint (load-latest-checkpoint workflow-id)
        workflow   (assoc (get-workflow workflow-id)
                          :workflow/status :RUNNING
                          :workflow/checkpoint nil)]
    (restore-agent-memory (:checkpoint/agent-memory checkpoint))
    (resume-from-task (:checkpoint/task-id checkpoint)
                      (:checkpoint/task-status checkpoint))
    workflow))
```

---

## 5. Concurrency Model

### 5.1 Workflow Scheduling

```clojure
(defprotocol WorkflowScheduler
  (enqueue [this workflow-id priority]
    "Add workflow to execution queue")
  
  (dequeue [this]
    "Get next workflow to execute based on priority and resources")
  
  (get-running-count [this]
    "Number of currently executing workflows")
  
  (get-queue-depth [this]
    "Number of pending workflows")
  
  (set-concurrency-limit [this limit]
    "Set max concurrent workflows"))
```

### 5.2 Resource Limits

```clojure
{:scheduler/max-concurrent-workflows  10
 :scheduler/max-tokens-per-minute     1000000
 :scheduler/max-cost-per-hour         100.00
 :scheduler/priority-boost-on-wait    true      ; increase priority for waiting workflows
 :scheduler/preemption-enabled        false}    ; can higher priority pause lower?
```

### 5.3 Scheduling Algorithm

1. **Priority queue**: Workflows ordered by priority (higher first)
2. **Resource check**: Before dequeue, verify budget available
3. **Fair share**: Optional: ensure no single workflow monopolizes resources
4. **Starvation prevention**: Boost priority for long-waiting workflows

```clojure
(defn select-next-workflow [scheduler]
  (let [pending   (get-pending-workflows scheduler)
        resources (get-available-resources scheduler)]
    (->> pending
         (filter #(can-afford? % resources))
         (sort-by effective-priority >)
         first)))
```

### 5.4 Concurrency Patterns

| Pattern              | Description                                    | Use Case                   |
|----------------------|------------------------------------------------|----------------------------|
| Serial               | One workflow at a time                         | Limited resources          |
| Parallel             | N workflows concurrently                       | Normal operation           |
| Pipeline             | Workflows in dependency order                  | Sequential features        |
| Fan-out/Fan-in       | Spawn sub-workflows, wait for all              | Parallel implementation    |

---

## 6. Operator Agent

### 6.1 Operator Responsibilities

The Operator agent is a special meta-agent that:

- Monitors all running workflows
- Responds to meta loop signals
- Issues workflow control commands
- Spawns new workflows when needed
- Reports status to humans

### 6.2 Operator Decision Model

```clojure
{:operator/triggers
 [{:signal        :high-error-rate
   :threshold     0.20
   :action        :pause-workflow
   :params        {:target :offending-workflow
                   :reason "Error rate exceeded 20%"}}
  
  {:signal        :budget-near-limit
   :threshold     0.90
   :action        :pause-all
   :params        {:reason "Approaching budget limit"}}
  
  {:signal        :conflicting-changes
   :condition     (fn [signals] ...)
   :action        :pause-and-escalate
   :params        {:require-human true}}
  
  {:signal        :test-failure-cluster
   :threshold     3
   :action        :spawn-diagnostic
   :params        {:workflow-type :debug}}]}
```

### 6.3 Operator Actions

| Action               | Effect                                          |
|----------------------|-------------------------------------------------|
| `:pause-workflow`    | Pause specific workflow                         |
| `:pause-all`         | Pause all running workflows                     |
| `:resume-workflow`   | Resume paused workflow                          |
| `:stop-workflow`     | Terminate workflow                              |
| `:restart-workflow`  | Fresh restart of workflow                       |
| `:spawn-workflow`    | Create new workflow (e.g., for debugging)       |
| `:adjust-priority`   | Change workflow priority                        |
| `:adjust-budget`     | Modify workflow resource limits                 |
| `:escalate`          | Request human intervention                      |
| `:notify`            | Send notification without action                |

---

## 7. Sub-Workflows

### 7.1 Spawning Sub-Workflows

Workflows can spawn children for:
- Parallel implementation of independent components
- Exploratory spikes before committing
- Diagnostic/debugging runs
- A/B testing of approaches

```clojure
(spawn-workflow parent-id
                {:spec {:type :implement-component
                        :component "auth-service"}}
                {:priority 5
                 :budget {:tokens 50000 :cost-usd 5.00}
                 :on-complete :notify-parent
                 :on-failure :notify-parent})
```

### 7.2 Parent-Child Relationships

```
Workflow A (parent)
├── Workflow B (child) ─► implements component X
├── Workflow C (child) ─► implements component Y
└── Workflow D (child) ─► implements component Z
    └── Workflow E (grandchild) ─► sub-task of Z
```

### 7.3 Aggregation Patterns

| Pattern          | Description                                    |
|------------------|------------------------------------------------|
| Wait-all         | Parent waits for all children to complete      |
| Wait-any         | Parent proceeds when first child completes     |
| Fire-and-forget  | Parent continues immediately, checks later     |
| Race             | First child to succeed wins, others stopped    |

---

## 8. Inter-Workflow Communication

### 8.1 Shared Artifact Access

Workflows can read artifacts from other workflows:

```clojure
;; In child workflow
(load-artifact {:artifact-id artifact-id
                :from-workflow parent-id})
```

### 8.2 Event Bus

Workflows can emit and subscribe to events:

```clojure
(defprotocol WorkflowEventBus
  (publish [this event]
    "Publish event to bus")
  
  (subscribe [this workflow-id event-pattern callback]
    "Subscribe to events matching pattern")
  
  (unsubscribe [this subscription-id]
    "Remove subscription"))

;; Event schema
{:event/type      keyword          ; :artifact-created, :phase-completed, etc.
 :event/source    uuid             ; workflow-id
 :event/timestamp inst
 :event/data      map}
```

### 8.3 Coordination Primitives

| Primitive        | Description                                    |
|------------------|------------------------------------------------|
| Barrier          | Multiple workflows wait at a sync point        |
| Semaphore        | Limit concurrent access to shared resource     |
| Lock             | Exclusive access to artifact/resource          |
| Channel          | Typed message passing between workflows        |

---

## 9. Configuration

### 9.1 System-Level Configuration

```clojure
{:async-workflows
 {:max-concurrent           20
  :default-priority         5
  :checkpoint-interval-ms   300000      ; 5 minutes
  :stale-workflow-timeout   86400000    ; 24 hours
  :enable-sub-workflows     true
  :max-sub-workflow-depth   3
  :enable-preemption        false}}
```

### 9.2 Per-Workflow Configuration

```clojure
{:workflow/config
 {:priority          7
  :timeout-ms        3600000             ; 1 hour max
  :budget            {:tokens 100000 :cost-usd 10.00}
  :checkpoint-policy :on-phase-complete  ; or :periodic, :always
  :failure-policy    :pause-and-notify   ; or :retry, :stop, :escalate
  :sub-workflow      {:allowed true
                      :max-children 5}}}
```

---

## 10. Observability

### 10.1 Workflow Metrics

| Metric                          | Type      | Description                    |
|---------------------------------|-----------|--------------------------------|
| `workflow.active.count`         | Gauge     | Currently running workflows    |
| `workflow.pending.count`        | Gauge     | Queued workflows               |
| `workflow.completed.count`      | Counter   | Total completed                |
| `workflow.failed.count`         | Counter   | Total failed                   |
| `workflow.duration.seconds`     | Histogram | Time to complete               |
| `workflow.phase.duration`       | Histogram | Time per phase                 |
| `workflow.tokens.consumed`      | Counter   | Total tokens used              |
| `workflow.cost.usd`             | Counter   | Total cost                     |

### 10.2 Workflow Events (for logging)

| Event                           | Level | When                           |
|---------------------------------|-------|--------------------------------|
| `:workflow/created`             | info  | New workflow created           |
| `:workflow/started`             | info  | Execution began                |
| `:workflow/paused`              | info  | Workflow paused                |
| `:workflow/resumed`             | info  | Workflow resumed               |
| `:workflow/stopped`             | info  | Workflow terminated            |
| `:workflow/completed`           | info  | Workflow finished successfully |
| `:workflow/failed`              | error | Workflow failed                |
| `:workflow/checkpointed`        | debug | Checkpoint created             |
| `:workflow/spawned-child`       | info  | Sub-workflow created           |

---

## 11. Deliverables

### Phase 0 (Foundations)

- [ ] Workflow schema
- [ ] Checkpoint schema
- [ ] Command schema

### Phase 1 (Domain)

- [ ] Workflow state machine
- [ ] Checkpoint manager
- [ ] Workflow controller protocol

### Phase 2 (Application)

- [ ] Workflow scheduler
- [ ] Operator agent implementation
- [ ] Sub-workflow coordination

### Phase 3 (Infrastructure)

- [ ] Persistent workflow store
- [ ] Event bus implementation
- [ ] Metrics emission

---

## 12. Open Questions

1. **Checkpoint storage**: In-memory vs persistent? How long to retain?
2. **Cross-workflow artifacts**: Copy-on-read or reference?
3. **Preemption**: Should high-priority workflows preempt lower ones?
4. **Failure propagation**: Should parent fail if child fails?
5. **Workflow versioning**: How to handle spec changes mid-execution?
