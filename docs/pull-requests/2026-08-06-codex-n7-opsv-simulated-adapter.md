<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: add deterministic OPSV staging adapter

## Overview

Add a side-effect-free simulated adapter and use it to prove the N7 staging
MCI discovers CPU and backlog signals and synthesizes an interoperable scaling
proposal.

## Motivation

The registered OPSV workflow had an inline project test double. N7 calls for a
deterministic staging adapter that exercises guarded ramp data without live
infrastructure and demonstrates the required HPA/KEDA proposal end to end.

## Layer

OPSV staging adapter and project integration composition.

## Depends on

- PR #1678 (OPSV event projection) — merged

## Changes in Detail

- Add a public functional constructor for the existing OPSV adapter port.
- Add a focused `opsv-adapter-simulated` Polylith component that replays
  immutable scenario values.
- Move candidate drivers, the environment fingerprint, and ramp observations
  into one application fixture instead of duplicating maps in test support.
- Compose the simulated component into the Miniforge staging integration run.
- Assert staging targeting, CPU and backlog discovery, `autoscaling/v2` HPA,
  backlog KEDA, recommend-only mode, and zero governed effects.

## Standards Audit

- Cross-component calls use public interface namespaces only.
- The adapter owns no mutable state and has no external-effect path.
- Scenario maps are assembled once in EDN and passed as values.
- The adapter constructor has one responsibility and no nested control flow.
- Changed namespaces satisfy clj-kondo and computed-stratum checks.
- The project-only integration dependency is declared in Polylith's
  `:necessary` set; no new workspace warning is introduced.

## Testing Plan

- Simulated adapter component: 1 test, 4 assertions.
- Phase OPSV suites: 11 tests, 53 assertions.
- OPSV project integration: 4 tests, 42 assertions.
- Pre-commit smoke: 339 tests, 1,285 assertions.
- GraalVM/Babashka compatibility: 8 tests, 606 assertions.
- Polylith reports only the four existing repository baseline warnings.
- Miniforge CLI uberjar builds successfully.

## Deployment Plan

No deployment action is required. The adapter is deterministic staging
infrastructure and the workflow remains recommend-only with no external
effects.

## Checklist

- [x] Staging execution requires no live infrastructure.
- [x] Discovery returns both CPU and backlog candidate drivers.
- [x] Guarded ramp observations flow through the seven-phase workflow.
- [x] Synthesis produces HPA/KEDA-compatible scaling recommendations.
- [x] Effective actuation remains recommend-only with zero effects.

## Follow-up

Implement the separately governed N7 actuation slice.
