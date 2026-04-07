# Workflow Methodologies

## Context

Miniforge currently supports only waterfall execution (canonical SDLC). Human software
organizations have converged on several distinct methodologies for shipping software.
Since organizations are effectively AI with human compute and management nodes, the same
process patterns should transfer to agent-based execution.

## Methodology Taxonomy

| Method | Planning | Execution | Key Differentiator |
|--------|----------|-----------|-------------------|
| **Waterfall** | Meta-planner → N parallel full-waterfall tracks | Sequential within each track | Planning is hierarchical; each "team" gets its own full pipeline |
| **Nimble** | Full waterfall planning to leaf tasks | Milestone-based scrum; parallel features; punt/keep at boundaries | Plan everything upfront, then execute adaptively |
| **Corporate Agile (Scrum)** | Sprint planning from backlog | Fixed-length sprints, WIP within sprint, retro feeds next sprint | Time-boxed iterations, velocity tracking |
| **Manifesto Agile** | Minimal upfront; continuous re-planning | Implement → discover → adjust → ship when ready | Continuous feedback loop, no fixed boundaries |
| **Kanban** | Pull from prioritized backlog | WIP limits, continuous flow, no sprints | Flow-based, throughput-optimized |

## Infrastructure Requirements

All methodologies beyond single-track waterfall require the fleet DAG orchestration
infrastructure (see `fleet-dag-orchestration.md`).

## Sequencing

1. **Canonical waterfall** — done (single track)
2. **Fleet DAG orchestrator** — enables all other methodologies
3. **Kanban** — simplest addition: WIP limits on the scheduler
4. **Nimble** — milestone partitioning + PM convergence gates
5. **Scrum** — backlog + sprint planning + retrospective feedback
6. **Manifesto agile** — dynamic DAG modification during execution (hardest)
