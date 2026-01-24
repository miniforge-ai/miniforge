# miniforge.ai — Product Specification

**Version:** 0.1.0  
**Status:** Draft  
**Author:** Christopher Lester  
**Date:** 2026-01-18  

---

## 1. Overview

### 1.1 What We're Building

miniforge.ai is an autonomous SDLC platform that converts human intent into production-grade software through coordinated AI agent teams operating over a governed control plane.

### 1.2 Key Differentiators

- **Not a chatbot**: Structured multi-agent orchestration, not conversational generation
- **Not a code generator**: Full SDLC coverage from intent to production observability
- **Not a CI/CD wrapper**: Self-directing with nested improvement loops

### 1.3 Success Criteria

1. Given a specification, produce deployable software with tests
2. Maintain full traceability from intent → artifact → deployment
3. Self-improve delivery process based on observed outcomes
4. Operate within defined cost, latency, and risk budgets

---

## 2. Architecture

### 2.1 Stratified Design

```
┌─────────────────────────────────────────────────────────┐
│ ADAPTERS: CLI, API, Webhooks, Event Handlers            │
├─────────────────────────────────────────────────────────┤
│ APPLICATION: Orchestrator, Use-Cases, Ports             │
├─────────────────────────────────────────────────────────┤
│ DOMAIN: Agents, Loops, Policies, Artifact Model         │
├─────────────────────────────────────────────────────────┤
│ FOUNDATIONS: Types, Schemas, Pure Utilities             │
└─────────────────────────────────────────────────────────┘
         ▼
┌─────────────────────────────────────────────────────────┐
│ INFRASTRUCTURE: LLM Clients, Git, K8s, Telemetry, DB    │
└─────────────────────────────────────────────────────────┘
```

### 2.2 Core Subsystems

| Subsystem       | Responsibility                                    | Stratum       |
|-----------------|---------------------------------------------------|---------------|
| Agent Runtime   | Execute agent logic, manage context/memory        | Domain        |
| Control Plane   | Orchestrate agents, enforce policies, schedule    | Application   |
| Artifact Store  | Version, attribute, and link all artifacts        | Infrastructure|
| Loop Engine     | Run inner/outer/meta loops with termination logic | Domain        |
| Policy Engine   | Evaluate gates, budgets, and governance rules     | Domain        |
| Telemetry       | Collect metrics, traces, and signals              | Infrastructure|

---

## 3. Domain Model

### 3.1 Agents

Each agent is a pure function: `(context, task) → (artifacts, decisions, signals)`

**Agent Schema:**

```clojure
{:agent/id          uuid
 :agent/role        keyword      ; :planner, :architect, :implementer, etc.
 :agent/capabilities #{keyword}  ; what this agent can produce
 :agent/memory      ref          ; scoped memory/context store
 :agent/config      map}         ; model, temperature, token budget, etc.
```

**Canonical Roles (implement in order):**

| Priority | Role        | Inputs                        | Outputs                           |
|----------|-------------|-------------------------------|-----------------------------------|
| P0       | Planner     | Intent, constraints           | Work plan, task graph             |
| P0       | Implementer | Task, context                 | Code artifacts                    |
| P0       | Tester      | Code, spec                    | Test artifacts, coverage report   |
| P1       | Architect   | Requirements                  | ADRs, interface definitions       |
| P1       | Reviewer    | Artifacts, policies           | Approval/rejection, feedback      |
| P2       | SRE         | Deployment spec               | Manifests, runbooks               |
| P2       | Release     | Artifacts, gates              | Promotion decisions               |
| P3       | Security    | Artifacts, threat model       | Findings, policy violations       |
| P3       | Historian   | All events                    | Decision log, learning records    |
| P3       | Operator    | Meta signals                  | Process improvements              |

### 3.2 Artifacts

All work products are first-class artifacts with provenance.

**Artifact Schema:**

```clojure
{:artifact/id         uuid
 :artifact/type       keyword     ; :spec, :code, :test, :adr, :manifest, etc.
 :artifact/version    string      ; semantic or hash-based
 :artifact/content    any         ; the actual artifact data
 :artifact/origin     {:intent-id uuid, :agent-id uuid, :task-id uuid}
 :artifact/parents    [uuid]      ; artifacts this derives from
 :artifact/children   [uuid]      ; artifacts derived from this
 :artifact/metadata   map}
```

**Artifact Types:**

| Type       | Description                          | Produced By    |
|------------|--------------------------------------|----------------|
| :spec      | Requirements, acceptance criteria    | Human/Planner  |
| :plan      | Work breakdown, task graph           | Planner        |
| :adr       | Architecture Decision Record         | Architect      |
| :code      | Source code                          | Implementer    |
| :test      | Test code and fixtures               | Tester         |
| :review    | Review feedback and decisions        | Reviewer       |
| :manifest  | Deployment/infrastructure manifests  | SRE            |
| :image     | Container image reference            | Build system   |
| :telemetry | Metrics, traces, logs                | Runtime        |
| :incident  | Incident/rollback records            | SRE/Operator   |

### 3.3 Tasks

Unit of work assigned to an agent.

**Task Schema:**

```clojure
{:task/id           uuid
 :task/type         keyword        ; :plan, :design, :implement, :test, :review, :deploy
 :task/status       keyword        ; :pending, :running, :completed, :failed, :blocked
 :task/agent        uuid           ; assigned agent
 :task/inputs       [uuid]         ; artifact IDs as input
 :task/outputs      [uuid]         ; artifact IDs produced
 :task/parent       uuid           ; parent task (for decomposition)
 :task/children     [uuid]         ; subtasks
 :task/constraints  map            ; budget, deadline, policies
 :task/result       map}           ; outcome, signals, errors
```

---

## 4. Control Loops

### 4.1 Inner Loop — Artifact Production

**Trigger:** Task assigned to agent  
**Scope:** Single artifact increment  
**Cycle:** Generate → Validate → Repair  

**Termination Conditions:**
- All validation gates pass → emit artifact, complete task
- Max iterations exceeded → escalate to outer loop
- Unrecoverable error → fail task with signal

**Validation Gates (configurable per artifact type):**

| Gate          | Applies To    | Check                                |
|---------------|---------------|--------------------------------------|
| Syntax        | :code, :test  | Parses without error                 |
| Lint          | :code, :test  | Passes configured linters            |
| Type-check    | :code         | Passes type checker (if applicable)  |
| Unit-test     | :code         | All unit tests pass                  |
| Security-scan | :code, :image | No critical/high vulnerabilities     |
| Policy-check  | all           | Conforms to organization policies    |

**Inner Loop State Machine:**

```
┌──────────┐    generate    ┌───────────┐
│ PENDING  │ ─────────────► │ GENERATED │
└──────────┘                └─────┬─────┘
                                  │ validate
                                  ▼
                           ┌───────────┐
                      ┌─── │ VALIDATED │ ───┐
                      │    └───────────┘    │
                  pass│                     │fail
                      ▼                     ▼
               ┌──────────┐          ┌──────────┐
               │ COMPLETE │          │  REPAIR  │
               └──────────┘          └────┬─────┘
                                          │ (loop back to GENERATED)
                                          │ or escalate after N attempts
```

### 4.2 Outer Loop — SDLC Delivery

**Trigger:** Intent/spec received  
**Scope:** Feature or deliverable  
**Cycle:** Plan → Design → Implement → Verify → Review → Release → Observe  

**Phase Definitions:**

| Phase     | Entry Condition          | Exit Condition               | Artifacts Produced       |
|-----------|--------------------------|------------------------------|--------------------------|
| Plan      | Spec received            | Work plan approved           | :plan                    |
| Design    | Plan approved            | ADRs approved                | :adr                     |
| Implement | Design approved          | Code passes inner loop       | :code                    |
| Verify    | Code produced            | Tests pass, coverage met     | :test, :coverage         |
| Review    | Verification complete    | Reviewer approves            | :review                  |
| Release   | Review approved          | Deployed to target env       | :manifest, :image        |
| Observe   | Deployed                 | Stability period passes      | :telemetry               |

**Outer Loop State Machine:**

```
SPEC ──► PLAN ──► DESIGN ──► IMPLEMENT ──► VERIFY ──► REVIEW ──► RELEASE ──► OBSERVE
           │         │           │            │          │          │           │
           └─────────┴───────────┴────────────┴──────────┴──────────┴───────────┘
                                      (rollback on failure)
```

### 4.3 Meta Loop — Self-Improvement

**Trigger:** Periodic or threshold-based  
**Scope:** Process and policies  
**Inputs:** Signals aggregated across runs  

**Signal Types:**

| Signal                  | Source                  | Indicates                        |
|-------------------------|-------------------------|----------------------------------|
| Review rejection rate   | Reviewer agent          | Quality issues in generation     |
| Repair iteration count  | Inner loop              | Validation gaps or bad prompts   |
| Test gap (post-release) | Production incidents    | Insufficient test coverage       |
| Rollback frequency      | Release agent           | Release process issues           |
| Human override rate     | All phases              | Agent decision quality           |
| Cost per artifact       | Telemetry               | Efficiency opportunities         |
| Latency per phase       | Telemetry               | Bottlenecks                      |

**Improvement Actions:**

| Action                    | Requires           | Rollout Strategy     |
|---------------------------|--------------------|----------------------|
| Prompt refinement         | Shadow evaluation  | Canary               |
| Policy threshold change   | Human approval     | Immediate            |
| Gate addition/removal     | Human approval     | Staged               |
| Agent role redefinition   | Human approval     | Shadow → Canary      |
| Escalation rule change    | Human approval     | Immediate            |

---

## 5. Control Plane

### 5.1 Responsibilities

1. **Scheduling**: Queue and dispatch tasks to agents
2. **Coordination**: Manage handoffs and dependencies between agents
3. **Policy Enforcement**: Evaluate gates and governance rules
4. **Budget Management**: Track and enforce cost, latency, risk limits
5. **Escalation**: Route failures and ambiguities appropriately
6. **Observability**: Emit events, metrics, and traces

### 5.2 Ports (Interfaces)

Define these as protocols/interfaces in Application layer:

```clojure
(defprotocol AgentExecutor
  (execute [this agent task context] "Run agent on task, return result"))

(defprotocol ArtifactStore
  (save [this artifact] "Persist artifact")
  (load [this id] "Retrieve artifact by ID")
  (query [this criteria] "Find artifacts matching criteria")
  (link [this parent-id child-id] "Establish provenance link"))

(defprotocol PolicyEngine
  (evaluate [this artifact policies] "Check artifact against policies")
  (get-gates [this artifact-type phase] "Get applicable gates"))

(defprotocol TaskQueue
  (enqueue [this task] "Add task to queue")
  (dequeue [this agent-role] "Get next task for role")
  (update-status [this task-id status result] "Update task state"))

(defprotocol TelemetryCollector
  (emit-event [this event] "Record event")
  (emit-metric [this metric] "Record metric")
  (emit-trace [this trace] "Record trace span"))
```

### 5.3 Configuration

```clojure
{:control-plane/id          uuid
 :control-plane/agents      [{:role :planner :model "claude-opus-4" :budget {:tokens 100000}}
                             {:role :implementer :model "claude-sonnet-4" :budget {:tokens 50000}}
                             ...]
 :control-plane/policies    {:max-inner-iterations 5
                             :max-outer-retries 3
                             :cost-budget-per-run 10.00
                             :require-human-approval #{:release :meta-change}}
 :control-plane/gates       {...}  ; per-phase validation gates
 :control-plane/escalation  {...}} ; failure routing rules
```

---

## 6. Implementation Phases

### Phase 0: Foundations (P0)

**Goal:** Types, schemas, and pure utilities

**Deliverables:**
- [ ] Core schemas: Agent, Artifact, Task (as Clojure specs or Malli)
- [ ] Artifact type registry
- [ ] Pure utility functions (ID generation, validation helpers)
- [ ] Configuration schema

**Exit Criteria:** All schemas validate, no I/O dependencies

---

### Phase 1: Domain — Agents & Inner Loop (P0)

**Goal:** Agent execution model and inner loop

**Deliverables:**
- [ ] Agent protocol/interface definition
- [ ] Planner agent implementation (simplest first)
- [ ] Implementer agent implementation
- [ ] Tester agent implementation
- [ ] Inner loop engine (generate → validate → repair)
- [ ] Validation gate framework

**Exit Criteria:** Can execute single agent task through inner loop with validation

---

### Phase 2: Domain — Outer Loop (P1)

**Goal:** Full SDLC delivery flow

**Deliverables:**
- [ ] Outer loop state machine
- [ ] Phase transition logic
- [ ] Task decomposition from plan
- [ ] Agent handoff coordination
- [ ] Rollback mechanism

**Exit Criteria:** Can take spec through Plan → Implement → Verify → complete

---

### Phase 3: Application — Control Plane (P1)

**Goal:** Orchestration and policy enforcement

**Deliverables:**
- [ ] Control plane orchestrator
- [ ] Task queue implementation
- [ ] Policy engine with gate evaluation
- [ ] Budget tracking (tokens, cost)
- [ ] Escalation handler

**Exit Criteria:** Can orchestrate multi-agent workflow with policy gates

---

### Phase 4: Infrastructure — Persistence & LLM (P1)

**Goal:** Concrete implementations of ports

**Deliverables:**
- [ ] LLM client (Claude API adapter)
- [ ] Artifact store (file-based initially, then DB)
- [ ] Git integration for code artifacts
- [ ] Telemetry collector (structured logging initially)

**Exit Criteria:** End-to-end flow persists artifacts, uses real LLM

---

### Phase 5: Adapters — CLI & API (P2)

**Goal:** User-facing interfaces

**Deliverables:**
- [ ] CLI for submitting specs and viewing status
- [ ] HTTP API for programmatic access
- [ ] Webhook handlers for external triggers

**Exit Criteria:** Can interact with system via CLI and API

---

### Phase 6: Domain — Meta Loop (P3)

**Goal:** Self-improvement capabilities

**Deliverables:**
- [ ] Signal aggregation across runs
- [ ] Improvement proposal generation (Operator agent)
- [ ] Shadow mode evaluation
- [ ] Canary rollout mechanism
- [ ] Audit trail for process changes

**Exit Criteria:** System can propose and evaluate process improvements

---

### Phase 7: Production Hardening (P3)

**Goal:** Production-ready deployment

**Deliverables:**
- [ ] Kubernetes deployment manifests
- [ ] Observability stack (metrics, traces, dashboards)
- [ ] Security hardening
- [ ] Multi-tenant isolation
- [ ] Disaster recovery

**Exit Criteria:** Deployable to production environment

---

## 7. Technical Constraints

### 7.1 Technology Stack

| Layer          | Technology                           |
|----------------|--------------------------------------|
| Language       | Clojure (JVM)                        |
| Structure      | Polylith (monorepo components)       |
| LLM            | Claude API (Anthropic)               |
| Persistence    | PostgreSQL + filesystem              |
| Queue          | In-process initially, then Redis/SQS |
| Orchestration  | Kubernetes                           |
| Observability  | OpenTelemetry                        |

### 7.2 Non-Functional Requirements

| Requirement       | Target                                      |
|-------------------|---------------------------------------------|
| Latency (inner)   | < 30s per iteration                         |
| Latency (outer)   | < 10min for simple feature                  |
| Cost transparency | Per-run cost visible and budgetable         |
| Auditability      | Full trace from intent to artifact          |
| Recoverability    | Resume from any phase after failure         |
| Extensibility     | New agent roles without core changes        |

---

## 8. Open Questions

1. **Memory architecture**: How do agents share context? Scoped vs global memory?
2. **Parallelism**: When can agents run concurrently vs sequentially?
3. **Human-in-the-loop**: Sync (blocking) vs async (notification) approval?
4. **Multi-repo**: How to handle artifacts spanning multiple repositories?
5. **Incremental delivery**: Can outer loop produce partial results?

---

## 9. Glossary

| Term           | Definition                                                    |
|----------------|---------------------------------------------------------------|
| Agent          | Specialized AI role with defined inputs, outputs, and context |
| Artifact       | Versioned, attributed work product with provenance            |
| Control Plane  | Orchestration layer managing agents, policies, and workflows  |
| Inner Loop     | Generate → Validate → Repair cycle for single artifact        |
| Outer Loop     | Full SDLC cycle from intent to production                     |
| Meta Loop      | Self-improvement cycle operating on process itself            |
| Gate           | Validation checkpoint that must pass before proceeding        |
| Port           | Interface definition in Application layer                     |
| Adapter        | Concrete implementation of a port                             |

---

## 10. References

- [Founder Prior Art](../founder-prior-art.md) — Conceptual foundation
- [Stratified Design](.cursor/rules/000-foundations/001-stratified-design.mdc) — Architectural principles
- [PR Layering](.cursor/rules/700-workflows/720-pr-layering.mdc) — Implementation workflow
