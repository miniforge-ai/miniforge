# I-DAG-ORCHESTRATION — DAG Executor with PR Lifecycle

**Status:** Informative
**Date:** 2026-02-03
**Version:** 1.0.0

Specifies implementation for executing multi-task plans as a DAG where
**task completion is defined by merge**, including automated PR lifecycle handling.

---

## 1. Purpose

Enable Miniforge to take a large, dependency-rich plan (unknown depth DAG) and drive it to completion by:

* Executing tasks in dependency order
* Running multiple tasks/PRs concurrently when safe
* Automatically iterating on review/CI feedback until merge
* Tracking per-task state, artifacts, evidence, and cost
* Producing aggregated final state (repo/commit set) and execution report

---

## 2. Core Design Principle

**A DAG node is not "generate code once." A DAG node is a task workflow that runs
until it reaches a terminal integration state (`:merged`).**

This addresses the fractal reality: each task contains a sub-cycle:
`dev ↔ reviewer/pair-dev ↔ tester → PR monitoring → fixes → merge`

---

## 3. Two Cooperating Components

### 3.1 DAG Scheduler

* Dependency ordering, readiness, parallel dispatch
* Budgeting, checkpointing, invalidation
* Observes task terminal outcomes via events

### 3.2 PR Lifecycle Controller

* Branch/PR creation, CI monitoring, review monitoring
* Comment triage, fix loops, push commits, merge
* Emits events to scheduler

---

## 4. Component Structure

```text
components/dag-executor/
├── src/ai/miniforge/dag_executor/
│   ├── interface.clj       ; Public API
│   ├── scheduler.clj       ; DAG scheduling loop
│   ├── state.clj           ; Run + task state management
│   ├── parallel.clj        ; Concurrency control + resource locks
│   └── result.clj          ; ok/err helpers

components/pr-lifecycle/
├── src/ai/miniforge/pr_lifecycle/
│   ├── interface.clj       ; Public API
│   ├── controller.clj      ; State machine for task→PR→merge
│   ├── ci_monitor.clj      ; CI status polling/events
│   ├── review_monitor.clj  ; Review/comment monitoring
│   ├── fix_loop.clj        ; Automated fix generation
│   ├── triage.clj          ; Comment triage (actionable vs not)
│   ├── merge.clj           ; Merge policy enforcement
│   └── events.clj          ; PR lifecycle events
```

---

## 5. State Models

### 5.1 DAG Run State

```clojure
{:dag/id uuid
 :run/id uuid
 :run/status [:pending :running :paused :completed :failed :partial]
 :run/tasks {task-id -> TaskWorkflowState}
 :run/merged #{uuid}
 :run/failed #{uuid}
 :run/skipped #{uuid}
 :run/metrics {:total-tokens :total-cost-usd :total-duration-ms}
 :run/checkpoint {:ref string}}
```

### 5.2 Task Workflow State (the fractal unit)

```clojure
{:task/id uuid
 :task/status
 [:pending
  :ready
  :implementing          ; inner loop generating code
  :pr-opening            ; creating branch/PR
  :ci-running            ; waiting for CI
  :review-pending        ; waiting for approvals
  :responding            ; fix loop on CI/review feedback
  :ready-to-merge
  :merging
  :merged                ; TERMINAL SUCCESS
  :failed                ; TERMINAL FAILURE
  :skipped]              ; dependency/policy skip

 :task/pr {:id string :url string :branch string :base-sha string :head-sha string}
 :task/attempts [{:attempt/id uuid :phase :implement|:fix :result :ok|:err :metrics {...}}]
 :task/fix-iterations nat-int
 :task/ci-retries nat-int}
```

---

## 6. Task Workflow State Machine

```text
:ready
   ↓
:implementing (inner loop until gates pass)
   ↓
:pr-opening (create branch, open PR)
   ↓
:ci-running (wait for CI events)
   ↓ pass              ↓ fail
:review-pending    :responding (fix loop)
   ↓ approved          ↑ push fix, retry CI
:ready-to-merge ←──────┘
   ↓
:merging
   ↓
:merged (TERMINAL SUCCESS)
```

On review "changes requested": transition to `:responding` for fix loop.

---

## 7. Automated Fix Loop (Critical for "Actually Works")

On actionable feedback (CI failure, review changes, comments):

1. **Build fix context pack:**
   * Current PR diff
   * CI logs summary / failing tests
   * Reviewer comment threads (actionable subset)
   * Task acceptance criteria
   * Dependency artifacts and decisions

2. **Invoke inner loop with "fix" intent:**
   * Generate patch
   * Run gates locally
   * Push commit

3. **Re-check CI/review until:**
   * Success → `:ready-to-merge`
   * Exceeds `:max-fix-iterations` → `:failed`

---

## 8. Comment Triage

**Actionable:**

* Requested code change
* Bug report
* Failing test / CI guidance
* Design constraint violation

**Non-actionable:**

* Compliments
* Stylistic preferences already enforced by formatter
* Questions answered by PR body

Policy: `:comment-triage :actionable-only` recommended.

---

## 9. Merge Policy

A PR may be auto-merged if:

* Required approvals met
* CI is green
* Task gates pass
* No unresolved actionable threads
* Branch is up-to-date per `:rebase-policy`

---

## 10. Parallelism and Safety

### 10.1 Readiness

Task is ready when all `:task/deps` are in `:run/merged`.

### 10.2 Concurrency Controls

* **Serialize repo writes by default** unless tasks declare non-overlapping files
* Allow parallel analysis/test-only tasks that don't write
* Use resource locks: `:repo-write` exclusive, `:exclusive-files [...]`

### 10.3 Branch Isolation

Each task runs in isolated workspace (git worktree) to prevent interference.

---

## 11. Invalidation and Rebase

When upstream merges move `main`:

* **Auto-rebase** attempts rebase, reruns CI
* If conflicts: run **rebase-fix cycle** or fail per policy
* Dependency API changes may restart task from `:implementing`

---

## 12. PR Lifecycle Events

```clojure
;; Events emitted by PR controller, consumed by scheduler
:pr/opened
:pr/ci-passed, :pr/ci-failed
:pr/review-approved, :pr/review-changes-requested
:pr/comment-actionable
:pr/merged, :pr/closed

;; Each includes:
{:dag/id :run/id :plan/id :task/id :pr/id :pr/url :sha :timestamp}
```

---

## 13. Integration Points

### Existing Components to Reuse

| Component | Purpose |
|-----------|---------|
| `task/graph.clj` | ready-tasks, topological-sort, dependency chains |
| `loop/inner.clj` | generate→validate→repair cycle |
| `agent/implementer.clj` | Code generation per task |
| `release-executor/` | PR creation patterns |

### New Components

| Component | Purpose |
|-----------|---------|
| `dag-executor/` | DAG scheduling, state, parallelism |
| `pr-lifecycle/` | PR controller, CI/review monitoring, fix loops |

---

## 14. Critical Files

| File | Purpose |
|------|---------|
| `components/dag-executor/` | DAG scheduling, state management |
| `components/pr-lifecycle/` | PR lifecycle management |
| `projects/miniforge/deps.edn` | Component dependencies |

---

## 15. Normative Deltas

This informative spec requires additions to normative specs:

### N2 — Workflow Execution Model

1. Task completion = integration terminal state (default `:merged`)
2. Executor MUST support automated CI/review fix iteration
3. Executor MUST enforce concurrency/resource constraints

### N3 — Event Stream & Observability

Add PR lifecycle events with correlation: `:dag/id :run/id :plan/id :task/id :pr/id :sha`

### N6 — Evidence & Provenance

Require evidence linkage for PR creation, CI results, reviews, merges, fix iterations

---

## 16. Verification

Integration tests:

* Diamond DAG with two PRs in flight → automated merge
* CI failure → fix loop → merge without human input
* Review "changes requested" → fix loop → merge
* Rebase behind main → auto-rebase → conflict repair → merge
* Budget exhaustion → checkpoint + partial status

---

## 17. Implementation Order (Minimal Path to "Works")

1. **Task Workflow state machine + PR controller** with polling, emitting events
2. **Fix loop on CI failures** (highest ROI)
3. **Fix loop on "changes requested" reviews**
4. **Comment triage + targeted fixes**
5. **Auto-rebase + conflict repair**
6. **Resource locks + safe parallelism**

This produces merged PRs with minimal manual intervention while preserving DAG execution semantics.
