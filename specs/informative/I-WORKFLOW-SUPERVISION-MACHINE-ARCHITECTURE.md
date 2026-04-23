<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# I-WORKFLOW-SUPERVISION-MACHINE-ARCHITECTURE

**Status:** Informative
**Date:** 2026-04-22
**Version:** 0.1.0-draft

Describes the target machine architecture behind the N1/N2/N5 amendments:
one authoritative execution machine per workflow run, separate live supervision,
separate degradation control, a cross-run learning loop, and a human supervisory
loop that operates as a peer runtime to the orchestrator.

---

## 1. Purpose

Provide an implementation-oriented mental model for the formal-machine refactor
without introducing another normative spec. This note explains how the
architectural pieces fit together and where future workflow-family selection and
convergence logic belongs.

## 2. Topology

```text
                    ┌──────────────────────────────┐
                    │     Human Supervisory Loop   │
                    │  (TUI / dashboard / UX agent)│
                    └──────────────┬───────────────┘
                                   │ query / bounded interventions
                                   ▼
┌────────────────────────────────────────────────────────────────────┐
│                          Supervisory State                        │
│                 canonical projections and attention               │
└──────────────────────────────┬─────────────────────────────────────┘
                               │
                               ▼
                    ┌──────────────────────────────┐
                    │         Orchestrator         │
                    │   sole transition authority  │
                    └───────┬───────────┬──────────┘
                            │           │
                            ▼           ▼
                 ┌────────────────┐  ┌────────────────┐
                 │ Execution FSM  │  │ Supervision FSM│
                 │ per workflow   │  │ per workflow   │
                 └────────┬───────┘  └────────┬───────┘
                          │                   │
                          └────────┬──────────┘
                                   ▼
                         ┌─────────────────────┐
                         │ Degradation Mode FSM│
                         │ global / workspace  │
                         └─────────────────────┘

         Observe outputs, evidence bundles, outcomes
                                   │
                                   ▼
                     ┌────────────────────────────┐
                     │ Learning Loop              │
                     │ cross-run improvement      │
                     └────────────────────────────┘
```

## 3. Machine Responsibilities

### 3.1 Execution machine

The execution machine owns one workflow run from start to terminal state.

Typical responsibilities:

- current phase or state
- legal next transitions
- retry and redirect budgets
- phase-output capture and checkpointing
- resume snapshot state

The execution machine carries the workflow graph selected for the run. For a
software-factory run that may be `:canonical-sdlc`, `:quick-fix`, or
`:review-first`. For ETL it may be `:financial-etl` or another ETL profile.

### 3.2 Supervision machine

The supervision machine tracks live governance and operational posture for a run.

Typical states:

- `:nominal`
- `:warning`
- `:paused-by-supervisor`
- `:awaiting-operator`
- `:halted`

The supervision machine reacts to stale progress, policy escalations, watchdog
signals, and operator interventions. It does not replace the execution machine;
it constrains or directs it through the orchestrator.

### 3.3 Degradation machine

The degradation machine is global or workspace-scoped rather than per-run.

Typical states:

- `:normal`
- `:degraded`
- `:safe-mode`

This machine constrains what the orchestrator is willing to do. For example,
safe mode may disable auto-merge, block nonessential external side effects, or
force more human approvals.

### 3.4 Intervention lifecycle

Interventions from humans or UX-side agents are modeled explicitly rather than
applied directly.

Typical lifecycle:

```text
:proposed → :pending-human → :approved → :dispatched → :applied → :verified
       └──────────────→ :rejected
                                      └──────────────→ :failed
```

This keeps operator actions auditable and makes “asked for pause” different from
“pause actually applied.”

## 4. Runtime Actors

### 4.1 Orchestrator

The orchestrator is the autonomous control-plane runtime.

It:

- selects or loads the active machine instances
- validates incoming events and interventions
- applies transitions
- emits durable events for projections and evidence
- coordinates adapters, registries, and watchdogs

### 4.2 Human supervisory loop

The human supervisory loop is a peer control-plane runtime exposed through the UX.

It:

- queries canonical projections
- monitors health, progress, and policy state
- requests bounded interventions
- audits results and evidence

It does not directly edit workflow state.

### 4.3 Learning loop

The learning loop is cross-run and downstream.

It:

- consumes Observe outputs, evidence bundles, and outcome histories
- evaluates heuristics and policy candidates
- produces recommendations, not live workflow transitions

This keeps live supervision separate from system learning.

## 5. Workflow Families, Profiles, and Convergence

The machine architecture is intended to support many workflow paradigms without
turning execution into a single gigantic universal graph.

### 5.1 Family and profile split

- `:software-factory`
  - `:canonical-sdlc`
  - `:quick-fix`
  - `:review-first`
- `:etl`
  - `:financial-etl`
  - future ETL profiles

### 5.2 Selection before execution

A run begins with workflow selection:

1. resolve family and profile
2. assemble policies and graph fragments
3. compile the execution machine
4. persist the initial machine snapshot

### 5.3 Convergence

Future convergence logic can evaluate multiple candidate profiles or graph
variants before execution starts. The selected candidate becomes the single
compiled execution machine for that run.

Mid-run re-planning can exist later, but only as an explicit transition and not
as out-of-band graph mutation.

## 6. Resume Model

Resume is anchored on the execution-machine snapshot, not a phase index.

The useful mental model is:

1. restore machine state and machine context
2. restore completed phase outputs and artifacts
3. rebuild projections
4. dispatch the next legal event

This works for linear workflows, skipped states, redirected states, and future
non-waterfall profiles without inventing a second hidden state machine in the
runner.

## 7. Suggested Implementation Sequence

1. Strengthen the shared FSM utilities and validation surface
2. Normalize workflow definitions into a canonical graph model
3. Compile the per-run execution machine from that model
4. Introduce the per-run supervision machine and intervention lifecycle
5. Keep the degradation machine as a separate constraint source
6. Move the learning loop fully out of live supervision concerns
7. Delete legacy direct status writes as each subsystem migrates
