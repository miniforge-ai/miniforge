<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: assemble immutable OPSV evidence

## Overview

Implements the canonical N6 OPSV evidence schema and a run-scoped assembly
boundary that preallocates the evidence identifier, accumulates references, and
publishes one immutable final bundle.

## Motivation

N7 requires every OPSV run to retain its complete event, artifact, capability,
and governed-effect trail. The identifier must exist before the first event, and
terminal finalization must neither lose accumulated references nor create a
second identity.

## Layer

Application — evidence-bundle assembly, validation, and publication.

## Changes in Detail

- Define closed Malli schemas for the complete N6 §2.8 OPSV evidence shape.
- Allocate a collision-safe evidence identifier before event accumulation.
- Accumulate run references atomically with set semantics.
- Validate complete reference preservation, artifact availability, workflow
  identity, governed-effect capability correlation, and the enclosing N6 bundle.
- Finalize exactly once with the preallocated identifier and canonical content
  hash, retrying safely when accumulation races with finalization.
- Return anomaly values for missing, invalid, or already-finalized assemblies.

## Testing Plan

- `bb pre-commit`
- `clojure -M:poly test brick:evidence-bundle`
- `PR_BASE_SHA=$(git rev-parse origin/main) PR_HEAD_SHA=$(git rev-parse HEAD) bb pr-budget`

The evidence-bundle tests run from the Miniforge, MiniForge Core, and Miniforge
TUI project compositions. The focused suite covers identity preservation,
immutability, canonical reference sets, malformed-input handling, artifact
existence, governed-effect correlation, and accumulated-reference preservation.

## Deployment Plan

No migration is required. The API is additive and is consumed by the subsequent
N7 workflow-integration stratum.

## Checklist

- [x] N6 §2.8 is represented by a closed canonical schema
- [x] Evidence identity is allocated before OPSV events
- [x] Accumulated references cannot be omitted at finalization
- [x] Referenced artifacts must exist
- [x] Governed effects correlate intent, OIR, and capability identifiers
- [x] Finalization is atomic and exactly once
- [x] Commit and PR reportable-line budgets pass
