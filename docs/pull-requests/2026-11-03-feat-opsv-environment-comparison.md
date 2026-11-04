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

## Deployment Plan

No execution authority or live effects. Runner admission, durable contract
retention, and native replica-membership projection follow in separate slices.
The API alone cannot prove that a caller retained its digest before effects.

## Related Issues/PRs

Base branch: `main`. Contract dependency: #2353, the approved N7 §6.3 amendment.

## Checklist

- [ ] Add contract and boundary regression tests.
- [ ] Run owner adversarial and applicable-standards reviews.
- [ ] Pass project contexts, hooks, packaging, CI, and full-head review.
