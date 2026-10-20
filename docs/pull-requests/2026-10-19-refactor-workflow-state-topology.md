<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: decompose workflow FSM

## Overview

Decompose the legacy workflow FSM before terminal publication admission. Separate
state identity/topology, graph traversal, projections, compilation, pipeline and
structural validation, and the legacy compatibility API into focused namespaces.

## Scope

Base: main, aligned to `ac186fcac`. No dependencies on open PRs. Preserve phase ordering,
explicit transitions, verdict guard order, retry/redirect accounting, and public
state identifiers. New implementation namespaces must have at most three inferred
strata. The FSM itself becomes a delegation surface with a retained raw-builder
test seam. This PR does not change terminal admission or claim N7 completion.

The user approved a one-time size exception for this scoped cleanup on
2026-10-20. The initial 158-line integration triggered mandatory whole-file
stratum normalization (870 reportable lines) and still failed the five-layer
gate. The exception permits the complete decomposition, not a design waiver.
All other gates remain mandatory; subsequent unrelated work retains normal limits.

## Verification plan

Trace default, empty, explicit-target, guarded failure, paused and terminal paths.
Compare raw topology and execute compiled-machine regression tests. Serial product
consumer tests, package verification, adversarial standards review, signed hooks,
exact-head review and full CI remain required.

Initial verification: four topology tests / 14 assertions pass. Before wiring the
extracted builder, 162 combinations of terminal flags, explicit success/failure
targets and guarded/unguarded phase names produced exactly equal old/new raw
topologies. Public identifier aliases and the private builder redefinition seam
are retained.

Integrated validation: all three workflow product consumers passed serially
(1m37s after the legacy model follow-up). The rebuilt CLI is 39,197,409 bytes.
Isolated packaged topology, compiled-FSM, validation, restored failure, DAG
checkpoint and start-refusal tests passed: 38 tests / 258 assertions, without production source
overlays. Adversarial review traced empty startup, default sequential success,
explicit targets, terminal fallback, guarded retries and pause/resume. Guard
registration remains in the compiler before resolution; extracted topology
contains keyword references only. No guard ordering or accounting changes.
Explicit regressions verify malformed pipelines never build topology and dangling
guard/action references never reach compilation. Structural diagnostics retain
their ordering and field shapes. Legacy results use named constructors; nested
traversal and duplicated projection construction are removed.
The legacy state, terminal-state and transition indexes now derive from the
machine configuration instead of repeating its policy. A parity regression
exercises every legacy transition against the actual engine.

## Deployment

Normal package rollout; no persisted-state migration.
