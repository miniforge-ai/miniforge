<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Discover native OPSV baseline and correlated scaling drivers

## Overview

Connect complete native Kubernetes capture and canonical environment projection
to CPU/backlog discovery without executing experiments or changing clusters.

## Motivation

The native backend needs the same drift-sensitive baseline representation during
discovery and later verification. The existing snapshot discovery API remains
available with its original shape.

## Changes in Detail

Implementation in progress. Compose existing bounded native acquisition,
projection and Prometheus clients. Require an explicit native target and replica
bounds; preserve originals and refuse partial or unbound discovery.

## Testing Plan

Simulated transports only. Cover target/bounds admission, canonical baseline,
originals, metric label binding, interrupted/failing reads and changed state.
All local JVM/build/hook runs use the shared slot. All PR gates remain required.

## Deployment Plan

Read-only API only; no backend activation, load, effects or cluster operations.

## Related Issues/PRs

N7 discovery and environment comparison; native capture #2366, projection #2370,
and preparation #2373 (not a dependency of this discovery implementation).

## Checklist

- [ ] Implement and adversarially review the native discovery composition.
- [ ] Verify source, package, fault and standards gates.
- [ ] Settle review, require fresh full review/CI, and owner-merge.
