<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Capture target-bound Kubernetes workload inventory

## Overview

Acquire the declared namespace, Deployment, complete Pod inventory and complete
ReplicaSet inventory through bounded read-only Kubernetes requests. Bind namespace
and Deployment UIDs to the independently supplied runtime target.
Recheck root UIDs before returning, under the same deadline. These sequential
observations do not claim an atomic cluster snapshot.

## Motivation

The captured-membership model needs a complete native inventory before a live
preparation adapter can use it. A label-filtered view can omit replicas.

## Changes in Detail

Reuse existing HTTPS settings, transport, response admission, shared deadline
and distinct-object resource limits. The new namespace-inventory profile rejects selector
overrides. Existing filtered fingerprint discovery keeps its original contract.
No request path is derived from an owner reference.
Shared selection admission also rejects contradictory or malformed remaining
item counts before derived reads. An empty continuation token cannot override
evidence that a list is incomplete.

Ownership: `phase-opsv` Kubernetes read-side boundary. Raw inventory does not
attest membership, configuration, health or execution authority. Later partitioning
must prove controller chains and preserve unrelated observations.

## Testing Plan

Use local/injected HTTP fixtures only. Test request bounds, target mismatch,
UID replacement and selector refusal. Cover unrelated, empty and Pending Pods,
deadlines, cancellation, fatal errors, and fixed read paths.
Recreate roots during collection and require refusal before returning data.
Run product, native, standards,
normal signed hook, CI and full-head review gates.

## Deployment Plan

Explicit target and Deployment name only. No default experiment, live-cluster
change or effect authority is introduced. Publish only after the captured
membership prerequisite merges and its inherited diff is excluded.

## Related Issues/PRs

N7 §6.3 and captured Deployment membership (#2359).
[Kubernetes collection completeness](https://kubernetes.io/docs/reference/using-api/api-concepts/#retrieving-large-results-sets-in-chunks).

## Checklist

- [ ] Bound and admit all read-only controller observations.
- [ ] Keep configuration, freshness evidence and effect authority distinct.
- [ ] Complete all local, owner, CI and full-review gates.
