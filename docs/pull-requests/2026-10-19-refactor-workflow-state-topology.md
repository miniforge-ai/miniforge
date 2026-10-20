<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: separate workflow state topology

## Overview

Extract execution-state construction from the six-stratum workflow FSM namespace.
This is the first dependency-directed cleanup before terminal publication admission:
state topology, then structural validation/projections, then admission behavior.

## Scope

Base: main `1b6c46641`. No dependencies on open PRs. Preserve phase ordering,
explicit transitions, verdict guard order, retry/redirect accounting, and public
state identifiers. New implementation namespaces must have at most three inferred
strata. The remaining legacy FSM is handled by the next cleanup slice; this PR
does not claim repository-wide standards closure or change terminal admission.

## Verification plan

Trace default, empty, explicit-target, guarded failure, paused and terminal paths.
Compare raw topology and execute compiled-machine regression tests. Serial product
consumer tests, package verification, adversarial standards review, normal commit
and PR budgets, signed hooks, exact-head review and full CI remain required.

Initial verification: four topology tests / 14 assertions pass. Before wiring the
extracted builder, 162 combinations of terminal flags, explicit success/failure
targets and guarded/unguarded phase names produced exactly equal old/new raw
topologies. Public identifier aliases and the private builder redefinition seam
are retained.

Integrated validation: all three workflow product consumers passed serially
(1m24s). The rebuilt CLI is 39,190,109 bytes. Isolated packaged topology and
compiled-FSM tests passed: 26 tests / 171 assertions, without production source
overlays. Adversarial review traced empty startup, default sequential success,
explicit targets, terminal fallback, guarded retries and pause/resume. Guard
registration remains in the compiler before resolution; extracted topology
contains keyword references only. No guard ordering or accounting changes.

## Deployment

Normal package rollout; no persisted-state migration.
