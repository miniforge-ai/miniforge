<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: Clarify OPSV recovery authority timing

## Overview

Separate pretrial approval of a bounded recovery plan from just-in-time issuance
of exact recovery grants. Base branch: main. Layer: normative contract.
Depends on the merged staging authority amendment and #2420.

## Motivation

A restore request needs the fresh Kubernetes resource version after the trial.
Cleanup of a newly created Job needs its server-assigned UID. Those values cannot
be known before the forward effect; demanding exact grants then prevents a
correctly scoped trial without making recovery safer.

## Approval Record

The product owner approved this clarification on 2026-11-06.
Independently approve and retain a bounded recovery plan before a trial.
Issue each exact recovery grant just before recovery using fresh provider
preconditions and proven resource identity.
No wildcard grants or reused forward authority. Failed recovery
authorization keeps forward effects fenced and records unresolved recovery.
Approval covers the contract and implementation, not live experiments or cluster changes.

## Changes in Detail

Amend N7 §5.5 and N10 §1.5 in place and update their versions and index.
Keep N8's separate recovery path and general mutation fence unchanged.
The retained plan bounds recovery but is not an ExecutionGrant or permission to
execute. Every recovery effect still needs a current allowing decision, exact
grant and durable transaction; original-baseline verification remains required.
No new event, evidence type, activation default or final-actuation exemption.

## Testing Plan

Review the trial, abort and safe-mode order against N7 §6.3, N8 §3.4.2 and the
adopted N10 profile. Add conformance obligations for fresh recovery preconditions,
resource-name reuse and refusal after a previously approved plan.
Run prose/Markdown checks, normal signed commit hooks, current-head CI and review.
Documentation only; no production code or executable test behavior changes.

## Deployment Plan

Contract clarification only. Runtime implementation follows separately with
offline tests. No provider writes, live experiments or credential changes.

## Related Issues/PRs

N7 spec completion; #2381 staging authority; #2420 governed replica proposals.

## Checklist

- [ ] Review cross-spec consistency and the entire actual diff against standards.
- [ ] Pass documentation checks and normal signed commit gates.
- [ ] Settle current-head review, fresh full review and CI; owner-merge.
