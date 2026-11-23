<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: admit current phase heartbeat facts

## Plan and scope

Typed foundation following the approved N3 heartbeat amendment. Add strict 2.0.0
admission, protected liveness/progress provenance, registry retention metadata,
and a profile-aware constructor. Preserve legacy construction without treating
its tick counters as progress. The current scheduler is not switched by this PR.

## Verification

Reject missing, malformed, retired and contradictory conditional fields without
consuming sequence positions. Verify constructor profile selection, allocation
anomaly propagation, scope identity, durable replay and duplicate publication.
Run all event-stream consumers serially, rebuilt isolated package tests, normal
signed hooks, inferred strata, standards scan, fresh review and all CI gates.

Four consumer projects passed serially. Fresh CLI build: 39,205,323 bytes;
isolated package tests: 20 tests, 158 assertions, no failures or errors.
Adversarial trace verified cold current construction, missing/unknown coverage,
identity allocation refusal, duplicate receipts, close/reopen and legacy fields.

## Deployment

Merge after the contract. Follow with the pure progress model and lifecycle-owned
scheduler/observer integration; no blanket producer migration or N7 completion
claim. All normal size limits apply.
