<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: implement OPSV domain policy

## Overview

Adds pure N7 risk scoring, bounded convergence, per-criterion verification, and
monotonic effective-actuation decisions to the OPSV component.

## Motivation

The workflow cannot safely synthesize or actuate policy until its core decisions
are deterministic, explainable, bounded, and independently testable.

## Layer

Domain — pure functions over the canonical OPSV foundation contracts.

## Depends on

- #1649 — merged

## Changes in Detail

- Compute normalized additive risk from explicit contributions and policy-owned
  thresholds, with the required environment, blast-radius, and actuation factors.
- Execute deterministic bounded convergence with typed terminal reasons and
  enforced measurement-window/repetition stabilization.
- Evaluate every success criterion and summarize confidence and caveats.
- Derive an effective mode that never exceeds requested autonomy and requires
  verification, all six N7 gates, capability, rollback, and postconditions for apply.

## Testing Plan

- Exercise every risk level and factor contribution.
- Exercise each convergence terminal reason and iteration bound.
- Exercise passing and failing criterion sets.
- Exercise every requested mode and each apply precondition.
- Run focused component tests and the full pre-commit gate.

## Deployment Plan

This is pure domain behavior with no external effects.

## Checklist

- [x] Risk output is normalized and explainable
- [x] Convergence is deterministic and bounded
- [x] Verification produces one result per criterion
- [x] Effective actuation is monotonic and fail-closed
- [x] Focused suite: 21 tests, 119 assertions, 0 failures/errors
- [x] Pre-commit suite passes
