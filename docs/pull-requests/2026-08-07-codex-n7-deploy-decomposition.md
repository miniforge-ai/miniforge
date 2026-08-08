<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: decompose deployment application flow

## Overview

Separates deployment target resolution, Kubernetes provider operations,
application flow, and phase-result projection before N7 adds grant and
effect-transaction enforcement.

## Motivation

The deploy interceptor mixed configuration, provider reads and writes, rollout
decisions, evidence construction, and phase orchestration in one nested
function. That shape made the irreversible mutation seam difficult to govern
or review safely.

The adversarial standards pass also found that rollout failure discarded the
pre-apply rollback evidence even though the same evidence survived success.

## Changes in Detail

- Resolve one validated target map while retaining context-free current-cluster
  operation.
- Isolate rollback reads, apply, rollout observation, and pod summaries in the
  provider namespace.
- Express apply and observation as a small data pipeline.
- Project deployment outcomes and evidence in one result namespace.
- Preserve rollback, rendered-manifest, and image evidence on deployment
  failure.
- Preserve apply/rollout telemetry events and failure result statuses.
- Keep the public phase registration and successful result shape stable.

## Testing Plan

- Phase-deployment component suite is green.
- Staged Kondo reports zero warnings and errors.
- Stratum lint reports no namespace above three layers.
- Polylith structure check reports zero errors and warnings.
- Pre-commit smoke and GraalVM/Babashka compatibility suites are green for
  every commit.
- PR budget is below 600 reportable lines.

## Deployment Plan

No migration is required. The runtime behavior and phase contract remain
compatible. Failure handling is deliberately stricter when rollback or pod
observation is malformed or unavailable. This PR creates the reviewable seams
consumed by the next N7 PR.

## Related Issues/PRs

Prerequisite decomposition for `work/ariadne-deploy-grant-enforcement.spec.edn`
after #1711.

## Checklist

- [x] Provider, application, and result concerns are separate.
- [x] Every changed implementation namespace has at most three strata.
- [x] Rollback evidence survives failed rollout observation.
- [x] Repeated target and outcome maps are centralized.
