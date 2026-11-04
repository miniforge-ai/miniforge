<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Admit and retain candidate verification environments

## Overview

Bind an opt-in host declaration to the actual verification invocation. Acquire
and admit a fresh baseline, then retain the comparison contract and observation
before invoking the candidate verification adapter.

## Motivation

N7 §6.3 requires host ownership of the original baseline and independent
contract digest. The host must confirm durable evidence before the first
candidate effect.

## Changes in Detail

- Keep legacy verification on exact baseline matching.
- Fail closed for an explicitly malformed, stale or unavailable declaration.
- Require target-correlated preparation and durable evidence acknowledgement.
- Compare candidate observations only with the original, host-retained contract.
- Retain both raw observations and semantic projections, with explicit comparison
  outcomes and links to the pre-effect report.
- Share adapter exception handling and drift payload construction with legacy
  verification. Preserve fatal errors and cancellation.

Configure `:opsv/verification-environment` in execution options. Its closed public
schema requires candidate and Experiment Pack hashes, the exact runtime target,
and exact permitted changes. Replica rules are optional but must declare both
candidate and recovery expectations. An explicitly nil declaration fails closed.

Candidate-aware adapters provide raw observations as
`:environment/raw-observations`, alongside the canonical fingerprint and target.
Preparation is read-only. The host confirms immutable publication and evidence
assembly acknowledgement before calling verification. Final observations and
comparison outcomes must also be retained before success can be returned.

The preparation comparison uses recovery expectations to admit the original
baseline. It does not execute or attest a rollback.

Ownership: `phase-opsv` application orchestration. Reuse comparison, adapter,
immutable artifact publication and evidence assembly owners. No new dependencies.
This does not provide native mutations or relax N4/N8/N10 effect authority.

## Testing Plan

Prove preparation, retention and verification ordering; no candidate callback on
failed admission or publication; stale/forged receipts, target drift, undeclared
changes, independent hashes and replica bounds. Preserve fatal/cancellation and
legacy behavior. Run both product contexts, isolated native package tests,
normal signed hooks, owner adversarial/standards review, CI and full-head review.

## Deployment Plan

Opt-in declaration only; absent declarations retain current behavior. Live
backend projection and guarded effect execution remain separate work.

## Related Issues/PRs

N7 §6.3, merged comparison and replica contracts (#2354–#2355), read-only
preparation port (#2356), canonical N6 artifact vocabulary support.

## Checklist

- [ ] Bind declared candidate/pack hashes to the actual request.
- [ ] Confirm immutable evidence before the candidate callback.
- [ ] Preserve original recovery expectations and legacy matching.
- [ ] Complete all local, owner, CI and review gates.
