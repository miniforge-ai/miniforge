<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Admit captured Kubernetes Deployment membership

## Overview

Check captured Pod → ReplicaSet → Deployment controller references against an
explicit target. Require actual UIDs, object identities and namespace identity;
label selectors alone never establish workload membership.

## Motivation

N7 §6.3 permits bounded replica replacement only when workload membership remains
proven. Existing captured fingerprints retain resource identities but do not
resolve the controller chain.

## Changes in Detail

Add a closed captured-observation boundary and pure identity-consistency model
for Deployments. Reuse native resource, list and runtime-target schemas. Reject
missing, ambiguous, stale, foreign and duplicate identities.

Ownership: `phase-opsv` Kubernetes read-side observation domain. No new dependency,
live acquisition, mutation, authority grant, or template/configuration verdict.
Other workload kinds remain unsupported and fail closed.

## Testing Plan

Test valid rolling replacement through distinct ReplicaSets and zero population.
Reject same-name UID replacement, namespace replacement, foreign owners,
ambiguous or non-controller references, and conflicting duplicate observations. Run product,
native package, owner standards/adversarial review, normal signed hooks, CI and
full-head review gates.

## Deployment Plan

Read-only captured observations only. Native controller acquisition and actual
Pod configuration projection remain separate work before live runner integration.

## Related Issues/PRs

N7 §6.3 and the candidate/recovery replica contract from #2355.
Kubernetes documents same-namespace UID ownership and ReplicaSet controller links:
[Owners and dependents](https://kubernetes.io/docs/concepts/overview/working-with-objects/owners-dependents/),
[ReplicaSet ownership](https://kubernetes.io/docs/concepts/workloads/controllers/replicaset/).

## Checklist

- [ ] Pin captured identity and membership behavior.
- [ ] Keep membership distinct from configuration, freshness and authority.
- [ ] Complete all local, owner, CI and review gates.
