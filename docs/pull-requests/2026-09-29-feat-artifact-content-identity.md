<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Bounded artifact content identity

## Scope

Expose a deterministic digest for data accepted by immutable artifact publication.
The digest preserves collection and scalar wire types while ignoring map and set ordering.
Callers can derive artifact identifiers without collapsing lists into vectors.
Digests identify content; they do not attest publication or grant authority.

## Standards gap analysis

Reuse the publication codec's size, depth, type, and round-trip limits before hashing.
Reject deferred sequences without evaluating them, including nested infinite sequences.
Keep the public interface thin and exception conversion in a named boundary.
Reuse the existing typed identity representation rather than duplicate identity maps.
The implementation retains three namespace strata and existing localized anomalies.

## Verification

Artifact tests pass in all three consuming projects.
Regression cases check ordering independence, scalar and collection distinctions, and rejected deferred or excessively
nested input.
All execution uses local data without external effects.
