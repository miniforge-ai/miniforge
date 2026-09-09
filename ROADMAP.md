<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Roadmap

Last updated: 2026-09-08

## Current Status

**Open source and in active use.** Miniforge is actively developed and
dogfooded on its own repository. The Clojure SDLC pipeline runs from a work
spec through implementation, verification, review, PR creation, review
monitoring, and merge.

## Specification Status

[SPEC_INDEX.md](specs/SPEC_INDEX.md) is the authoritative specification map.
The normative text for N1–N7 is complete; N8–N13 are draft, and N14–N15
contain draft or explicitly speculative contracts.

“Complete” describes a contract document, not blanket implementation
conformance. Each normative spec's informative Annex A records known
implementation gaps. This roadmap does not maintain a second set of percentage
estimates because they quickly diverge from the code and conformance evidence.

## N7 Implementation Status

Implemented and verified:

- canonical OPSV contracts and content hashing
- pure risk, convergence, verification, and effective-actuation decisions
- the registered seven-phase `opsv` 1.0.0 workflow
- required lifecycle/domain event projection and evidence assembly
- a deterministic staging path that discovers CPU and backlog signals and
  emits an HPA/KEDA-compatible proposal without external mutation

Remaining delivery work:

1. Governed PR and Kubernetes actuation, rollback, and postcondition effects.
2. The six canonical CLI commands, Fleet TUI drill-down, policy-state
   projection, and drift detection.
3. The agent-invocation and phase-budget dogfood experiment.

The Ariadne deployment authority path is implemented, but OPSV still needs its
own real provider and Kubernetes adapters before N7 is end-to-end conformant.

## Active Delivery Priorities

[work/QUEUE.md](work/QUEUE.md) is the generated, authoritative delivery queue.
It derives readiness and ordering from active work specs and their dependency
metadata. [work/themes.edn](work/themes.edn) describes the current initiatives.

## How to Contribute

Active roadmap items are backed by work specs in `work/`. Each `.spec.edn` file
describes its scope, constraints, acceptance criteria, priority, and
dependencies.

1. Read [CONTRIBUTING.md](CONTRIBUTING.md) for setup, conventions, and the PR process.
2. Pick a ready work spec from [work/QUEUE.md](work/QUEUE.md).
3. Check `work/in-progress/` to avoid duplicating active work.
4. Open an issue or discussion referencing the spec before starting large items.
