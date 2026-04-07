# feat: DAG Executor with PR Lifecycle Orchestration

## Overview

Implements DAG-based multi-task execution where task completion is defined by PR merge,
including automated PR lifecycle handling with CI/review fix loops.

## Motivation

Enable Miniforge to take a large, dependency-rich plan and drive it to completion by:

- Executing tasks in dependency order
- Running multiple tasks/PRs concurrently when safe
- Automatically iterating on review/CI feedback until merge
- Tracking per-task state, artifacts, evidence, and cost
- Producing aggregated final state and execution report

The core insight: **A DAG node is not "generate code once." A DAG node is a task workflow
that runs until it reaches a terminal integration state (`:merged`).**

## Changes in Detail

### New Components

**dag-executor** (`components/dag-executor/`)

- `result.clj` - ok/err result monad pattern
- `state.clj` - Task workflow state machine with valid transitions
- `parallel.clj` - Semaphore-based resource locking for safe concurrent execution
- `scheduler.clj` - DAG scheduling loop with dependency tracking
- `interface.clj` - Public API

**pr-lifecycle** (`components/pr-lifecycle/`)

- `events.clj` - Event types and event bus for PR lifecycle
- `triage.clj` - Comment classification (actionable vs non-actionable)
- `ci_monitor.clj` - CI status polling via gh CLI
- `review_monitor.clj` - Review/comment monitoring
- `fix_loop.clj` - Automated fix generation for CI failures and review feedback
- `merge.clj` - Merge policy enforcement
- `controller.clj` - Main lifecycle controller (state machine)
- `interface.clj` - Public API

### Spec Updates

**N2-workflows.md** - Added Section 13 "DAG-Based Multi-Task Execution":

- 13.1 Task Completion Definition (`:merged` as terminal state)
- 13.2 Task Workflow State Machine
- 13.3 Automated CI/Review Fix Iteration
- 13.4 Concurrency and Resource Constraints
- 13.5 Rebase and Conflict Handling

**N3-event-stream.md** - Added Section 3.10 "PR Lifecycle Events":

- All PR lifecycle events with DAG correlation fields
- Events: pr/opened, pr/ci-passed, pr/ci-failed, pr/review-approved,
  pr/review-changes-requested, pr/comment-actionable, pr/fix-pushed, pr/merged, pr/closed

**N6-evidence-provenance.md** - Added Section 2.7 "DAG Orchestration Evidence":

- 2.7.1 DAG Run Evidence
- 2.7.2 Task Workflow Evidence (PR lifecycle, CI results, review results, fix iterations)
- 2.7.3 Merge Evidence

**New Informative Spec**: `specs/informative/I-DAG-ORCHESTRATION.md`

### Modified Files

- `projects/miniforge/deps.edn` - Added component dependencies

## Testing Plan

- `bb test` - Unit tests for new components
- Integration tests (future):
  - Diamond DAG with two PRs in flight -> automated merge
  - CI failure -> fix loop -> merge without human input
  - Review "changes requested" -> fix loop -> merge
  - Budget exhaustion -> checkpoint + partial status

## Deployment Plan

No deployment steps; this adds new components and spec updates.

## Related Issues/PRs

- I-DAG-ORCHESTRATION informative spec

## Checklist

- [x] dag-executor component implemented
- [x] pr-lifecycle component implemented
- [x] N2 normative spec updated
- [x] N3 normative spec updated (PR lifecycle events)
- [x] N6 normative spec updated (DAG/PR evidence)
- [x] I-DAG-ORCHESTRATION informative spec created
- [ ] Integration with workflow phase_executor (future work)
- [ ] End-to-end integration tests
