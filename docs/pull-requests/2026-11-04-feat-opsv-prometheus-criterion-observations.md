<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Acquire declared Prometheus criterion observations

## Overview

Compose bounded Prometheus windows into explicitly declared numeric criterion
observations for the native OPSV backend, preserving original measurements.
API surface class 1: internal EDN and trusted-process composition, not an external wire API.

## Motivation

Native verification needs real metric observations without guessing aggregates,
rewriting criteria after measurement or fabricating success/confidence/evidence.

## Changes in Detail

Implementation in progress. Explicit criterion/window/reduction declarations
and independent target/service label bindings precede acquisition. Reuse the
existing Prometheus client and bounded data admission. Preserve provenance and
original samples alongside numeric observations. No verification verdict.

## Testing Plan

Simulated transport only. Test declared reductions, label binding, no partial
results, bounded admission, invalid grids, interruption and provenance. Serialize
all local JVM/build/hook runs; preserve every review and merge gate.

## Deployment Plan

Read-only measurement only. No load, experiment, mutation, authority, evidence
publication or automatic backend activation.

## Related Issues/PRs

N7 verification criteria; bounded Prometheus client and native discovery #2374.

## Checklist

- [ ] Implement and adversarially review the measurement composition.
- [ ] Complete local source, project, package, fault and standards gates.
- [ ] Settle review, require fresh full review/CI, and owner-merge.
