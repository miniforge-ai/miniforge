<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: implement OPSV application transformations

## Overview

Implement the seven pure OPSV application transformations behind an injected
observability and guarded-load port.

## Motivation

The N7 contracts and domain policy are available. This slice composes them into
the application value flow that lifecycle interceptors can execute next.

## Layer

Application orchestration.

## Depends on

- PR #1654 (N7 OPSV governance gates) — merged

## Changes in Detail

- Define the collision-free namespaced vocabulary for seven OPSV steps.
- Define an injected port for observability discovery and guarded load ramps.
- Compose risk, convergence, policy synthesis, verification, and
  recommend-only actuation from the established N7 contracts.

## Testing Plan

- Focused Polylith tests for all application transformations.
- Stub-port value-flow execution with deterministic inputs and no external I/O.
- `bb pre-commit` and full GitHub CI.

## Deployment Plan

No deployment action is required. The value flow remains recommend-only and no
concrete boundary adapter ships in this slice.

## Related Issues/PRs

- N7 Operational Policy Synthesis implementation series.

## Checklist

- [x] The application vocabulary defines the exact seven OPSV step keys.
- [x] Every OPSV application transformation preserves the value flow.
- [x] The application layer depends only on the injected OPSV adapter port.
- [x] Synthesis produces HPA/KEDA-compatible recommendations.
- [x] Effective mode remains recommend-only with zero external effects.
- [x] Standards audit, adversarial review, focused tests, and pre-commit pass.

## Follow-up

A dependent PR will register the shared lifecycle interceptors, N3 OPSV event
emission, and versioned workflow resource. The following PR will then supply
the deterministic simulated staging adapter and minimum conforming workflow.
