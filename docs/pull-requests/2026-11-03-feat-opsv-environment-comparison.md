<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Pinned OPSV environment comparison

## Overview

Add pure, content-bound comparison contracts for canonical OPSV fingerprints.
This is the fingerprint stratum of candidate-aware verification, not a live runner.

## Motivation

An expected configuration change must not hide unrelated drift or replace the
recovery baseline. Exact candidate expectations and a separately pinned contract
digest keep those concerns separate.

## Changes in Detail

- Keep cluster and node-pool identity invariant; permit exact image/config changes.
- Bind contracts to the run, attempt, candidate, pack, and runtime target.
- Compare candidate observations against the expected state and recovery
  observations against the retained baseline.
- Require the caller's independently retained digest; reject altered contracts.

Ownership: `phase-opsv`, which owns verification admission and adapter contracts.
The comparison rules are pure. There is no new cross-brick dependency.
Affected products: `miniforge` and `miniforge-tui`.
The public boundary validates; pure internal rules trust admitted values.

## Testing Plan

Pin exact changes, unrelated drift, stale bindings, changed contracts, missing
values, recovery-baseline substitution, and closed input shapes. Run structural
checks and both affected project contexts, normal hooks, and packaged proof.

Completed: focused source tests (8 tests, 42 assertions), workspace structure,
and all `phase-opsv` tests in both product contexts. A fresh CLI build passed
27 packaged tests with 175 assertions from an isolated directory, without any
production source overlay. The packaged suites also cover the existing
verification, drift publication, and phase interface contracts.

## Deployment Plan

No execution authority or live effects. Runner admission, durable contract
retention, and native replica-membership projection follow in separate slices.
The API alone cannot prove that a caller retained its digest before effects.
Acquisition must independently establish the observed target and native resource
membership. This slice does not interpret raw Kubernetes fingerprints or permit
replica churn. The current runner still uses exact-baseline matching.

## Related Issues/PRs

Base branch: `main`. Contract dependency: #2353, the approved N7 §6.3 amendment.

## Checklist

- [x] Add contract and boundary regression tests.
- [x] Run owner adversarial and applicable-standards reviews.
- [ ] Pass project contexts, hooks, packaging, CI, and full-head review.
