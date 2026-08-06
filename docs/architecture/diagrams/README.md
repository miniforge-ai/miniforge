<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Architecture Diagrams — As-Built and Target

Two suites, one palette and legend (legend on panel A1). Solid
strokes mean the thing exists and works; dashed means partial or
scaffold; dotted means spec-only. The point of drawing both
architectures side by side is the delta — panel T3 carries it
explicitly, along with the known contradictions the design reviews
have on record.

**As-built** (what the code does today):

1. [A1 — The Polylith estate](a1-estate.svg) — bases, component
   subsystems, deployable projects, consumers (thesium via
   `bb-utils`/`messages`, miniforge-control via `contracts/`).
2. [A2 — The core loop](a2-core-loop.svg) — spec in, PR out:
   work-spec kanban → workflow FSM → DAG orchestrator → phase
   interceptors → agents → LLM backends → gates/evidence →
   release/PR, with the stage → namespace mapping.
3. [A3 — Persistence & integrations](a3-data-integrations.svg) —
   `~/.miniforge`, repo `.miniforge/`, `work/`, `contracts/` golden
   fixtures, event-stream archive, R2 harvest; LLM backends,
   `gh`/git, OCI runtimes, MCP surfaces, ETL connectors.

**Target** (what the specs design; sources: product-vision,
N-specs + deltas, fleet specs, deployment/licensing model):

1. [T1 — Five planes & deployment shapes](t1-five-planes.svg) —
   OSS factory engine, operator console, Fleet
   coordination/governance, Data Foundry, eval seam
   (workbench-contract + minibench); workstation/CI vs on-prem k8s
   vs standalone-product shapes.
2. [T2 — Run hierarchy, loops, governance](t2-runs-loops.svg) —
   Spec → MiniforgeRun → WorkflowRun DAG → TaskNode/AgentSession;
   inner/outer/meta loops; the FSM roster; policy packs → gates →
   evidence; the intervention round-trip.
3. [T3 — Built-vs-unbuilt delta & contradiction register](t3-delta.svg)
   — spec-by-spec completion, subsystem status board, and the nine
   recorded contradictions/risks from the design and governance
   reviews.

Diagrams follow the same conventions as
`thesium-career/docs/architecture/diagrams/` (house draw.io style:
zone bands, pale typed fills, labeled color-coded edges).
