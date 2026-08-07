<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: project OPSV lifecycle events

## Overview

Project the required N3 OPSV domain events at successful phase boundaries and
persist their identifiers in the durable evidence assembly.

## Motivation

The OPSV lifecycle executed end to end but exposed only generic workflow
telemetry. N7 requires domain-specific event records that carry the evidence
bundle identity, retain the complete risk result, and survive checkpointing.

## Layer

OPSV application event projection and publication.

## Depends on

- PR #1675 (OPSV model decomposition) — merged
- PR #1677 (project integration registration) — merged

## Changes in Detail

- Construct the planned, started, load-step, convergence, policy,
  verification, and actuation event records from phase outputs.
- Publish events only after successful phase transformations.
- Accumulate published event IDs in the canonical evidence assembly.
- Reuse the shared bounded confidence classifier rather than duplicate its
  threshold logic.
- Exercise the registered project integration path with exact event
  cardinalities, evidence linkage, checkpoint restoration, and side-effect
  freedom assertions.

## Standards Audit

- Event construction, publication, and phase orchestration remain separate.
- Every changed implementation namespace has at most three computed strata.
- Each event payload is assembled once; no repeated policy or evidence maps
  are embedded across phase functions.
- Event publication failures are not swallowed or converted to false success.
- Legacy evidence failures are normalized to canonical anomalies at the
  component boundary, preserving the original failure as diagnostic data.
- Runtime objects remain outside checkpointed execution input.
- Default actuation remains `:recommend-only` with no governed effects.

## Testing Plan

- Phase-OPSV tests pass in both composed projects: 10 tests and 50 assertions
  per project, including fail-closed publication and evidence regressions.
- Project integration passes: 310 tests and 1,099 assertions, including exact
  OPSV event cardinalities and durable evidence references.
- The full Miniforge project suite passes across all 87 composed bricks.
- Poly reports only the four known repository baseline warnings.
- Changed-file clj-kondo and stratum lint report no findings.
- Pre-commit smoke passes 339 tests and 1,285 assertions; GraalVM compatibility
  passes 8 tests and 602 assertions.
- The Miniforge CLI uberjar builds successfully.

## Deployment Plan

No deployment action is required. The change adds deterministic event records
to the existing successful workflow path and preserves the current actuation
posture.

## Checklist

- [x] Required N3 event types have deterministic phase-boundary cardinalities.
- [x] The planned event retains the full risk result record.
- [x] Domain event IDs are durable evidence references.
- [x] No runtime adapter or evidence-store object is checkpointed.
- [x] Guardrail aborts and missing confidence thresholds are covered.

## Follow-up

Add the deterministic simulated OPSV adapter as the next independently
reviewable N7 slice.
