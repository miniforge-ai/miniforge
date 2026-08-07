<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: decompose the OPSV phase model

## Overview

Decompose the OPSV application transformations into focused, stratified
namespaces before adding domain event projection.

## Motivation

The phase model mixed anomaly continuation, policy construction, and
verification inside long orchestration functions. Although the transformations
were deterministic, that shape obscured the seven-step value flow and made
future event projection harder to review safely.

## Layer

Application transformation structure.

## Depends on

- PR #1657 (OPSV lifecycle and workflow) — merged

## Changes in Detail

- Extract anomaly-preserving continuation into a focused leaf namespace.
- Extract operational policy construction from phase orchestration.
- Extract verification criteria, evaluation, and result attachment.
- Centralize bounded confidence classification with nil/type-safe fallback.
- Replace nested anonymous phase bodies with named transformation helpers.
- Keep runtime adapter resolution opts-first with the existing input fallback.

## Testing Plan

- Phase-OPSV tests pass in both composed projects (6 tests, 37 assertions).
- All four commits passed Poly structure, staged Clojure lint, stratum lint,
  smoke tests, and GraalVM compatibility gates.
- The full Miniforge project test suite passes across all 87 bricks.
- The Miniforge CLI uberjar builds successfully.

## Deployment Plan

No deployment action is required. This refactor preserves phase inputs,
outputs, anomaly behavior, and the default recommend-only posture.

## Checklist

- [x] Every implementation namespace uses at most three computed strata.
- [x] The seven public phase transformations preserve their contracts.
- [x] Runtime adapter precedence remains backward compatible.
- [x] No policy or verification map shape is duplicated in orchestration.

## Follow-up

The next dependent PR projects and persists the required N3 OPSV events.
