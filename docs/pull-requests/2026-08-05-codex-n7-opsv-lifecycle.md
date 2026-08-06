<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: register the OPSV lifecycle and workflow

## Overview

Register the seven deterministic OPSV transformations with the shared phase
lifecycle and ship the exact versioned workflow resource with durable,
run-scoped evidence identity.

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
- Preallocate the run's N6 evidence identity before the first OPSV phase and
  reject unmatched external identifiers.
- Keep runtime adapters and mutable evidence stores outside durable execution
  input so workflow checkpoints remain readable and resumable.
- Apply the existing bounded convergence contract at the Experiment Pack
  boundary and degrade incomplete confidence projections to `:low`.
- Package `:opsv` version `1.0.0` as the exact seven-phase pipeline followed
  by `:done`, and include it in the Miniforge project and development bases.
- Correct the OPSV governance pack's phase selectors to use the registered
  `:opsv/*` identifiers; the previous unqualified selectors could never match
  these phases during phase-scoped policy evaluation.
- Decompose lifecycle evidence and result construction into focused
  namespaces; centralize repeated result and metric shapes.

## Testing Plan

- Run the complete `project:miniforge` Polylith test suite.
- Verify exact resource loading and an end-to-end shared-runner execution.
- Assert exact phase execution, durable evidence restoration, runtime adapter
  isolation, and side-effect-free actuation.
- Run focused lint, stratum, Poly, pre-commit, and CLI build gates.

## Deployment Plan

No deployment action is required. OPSV continues to default to
`:recommend-only`, and this slice provides no mutating adapter.

## Checklist

- [x] Seven namespaced OPSV phases use the shared registry and runner.
- [x] `:opsv` version `1.0.0` loads the exact pipeline followed by `:done`.
- [x] Governance pack phase selectors match the registered phase identifiers.
- [x] End-to-end actuation remains recommend-only with zero external effects.
- [x] Full project tests pass.

## Follow-up

The next dependent PR projects the required N3 OPSV events and completes the
phase-model standards decomposition. The simulated adapter and staging minimum
conforming implementation follow that application-layer slice.
