<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: register the OPSV lifecycle and workflow

## Overview

Register the seven deterministic OPSV transformations with the shared phase
lifecycle, project their required N3 events at successful boundaries, and ship
the exact versioned workflow resource.

## Motivation

The OPSV application value flow existed, but the shared workflow runner could
not load or execute it. This slice supplies the integration boundary without
adding a concrete infrastructure adapter or permitting external mutation.

## Layer

Application integration and workflow wiring.

## Depends on

- PR #1655 (OPSV application transformations) — merged

## Changes in Detail

- Register `:opsv/discover` through `:opsv/actuate` as shared phase
  interceptors with deterministic budgets and anomaly-preserving results.
- Emit the N3 OPSV experiment, convergence, policy, verification, and
  actuation events only after successful phase boundaries.
- Preallocate the run's N6 evidence identity before the first OPSV event,
  correlate emitted event references, and reject unmatched external IDs.
- Package `:opsv` version `1.0.0` as the exact seven-phase pipeline followed
  by `:done`, and include it in the Miniforge project and development bases.
- Correct the OPSV governance pack's phase selectors to use the registered
  `:opsv/*` identifiers; the previous unqualified selectors could never match
  these phases during phase-scoped policy evaluation.

## Testing Plan

- Run the complete `project:miniforge` Polylith test suite.
- Verify exact resource loading and an end-to-end shared-runner execution.
- Assert one shared start/completion pair per OPSV phase and the exact domain
  event counts, evidence correlation, and side-effect-free actuation result.
- Run focused lint, stratum, Poly, pre-commit, and CLI build gates.

## Deployment Plan

No deployment action is required. OPSV continues to default to
`:recommend-only`, and this slice provides no mutating adapter.

## Checklist

- [x] Seven namespaced OPSV phases use the shared registry and runner.
- [x] Successful boundaries project the required N3 OPSV events.
- [x] `:opsv` version `1.0.0` loads the exact pipeline followed by `:done`.
- [x] Governance pack phase selectors match the registered phase identifiers.
- [x] End-to-end actuation remains recommend-only with zero external effects.
- [x] Full project tests pass.

## Follow-up

The next dependent PR supplies the deterministic simulated adapter and staging
minimum conforming implementation required to complete the workflow work spec.
