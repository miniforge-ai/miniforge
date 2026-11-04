<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Capture target-bound Kubernetes Deployment membership

## Overview

Acquire the declared namespace, Deployment, selected Pods and their ReplicaSets
through bounded read-only Kubernetes requests. Admit their actual controller
UID chain against the independently supplied runtime target.

## Motivation

The captured-membership model needs a native acquisition boundary before a live
preparation adapter can use it. Label selection alone cannot prove membership.

## Changes in Detail

Reuse existing HTTPS settings, transport, response admission, shared deadline
and resource-count limits. Fetch only known Deployment/ReplicaSet resource kinds
and validate every owner-derived resource name before making its request.
Shared selection admission also rejects contradictory or malformed remaining
item counts before derived reads. An empty continuation token cannot override
evidence that a list is incomplete.

Ownership: `phase-opsv` Kubernetes read-side boundary. No mutations, experiments,
authority grants, configuration projection or runtime-health verdicts.

## Testing Plan

Use local/injected HTTP fixtures only. Test request bounds, target mismatch,
UID replacement and unsupported owners. Cover empty and Pending Pods, deadlines,
cancellation, fatal errors, and refusal of arbitrary owner-derived reads.
Run product, native, standards,
normal signed hook, CI and full-head review gates.

## Deployment Plan

Explicit target and Deployment name only. No default experiment, live-cluster
change or effect authority is introduced. Publish only after the captured
membership prerequisite merges and its inherited diff is excluded.

## Related Issues/PRs

N7 §6.3 and captured Deployment membership implementation.
[Kubernetes collection completeness](https://kubernetes.io/docs/reference/using-api/api-concepts/#retrieving-large-results-sets-in-chunks).

## Checklist

- [ ] Bound and admit all read-only controller observations.
- [ ] Keep configuration, freshness evidence and effect authority distinct.
- [ ] Complete all local, owner, CI and full-review gates.
