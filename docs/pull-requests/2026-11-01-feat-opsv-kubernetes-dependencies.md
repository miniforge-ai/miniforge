<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: model native Kubernetes configuration dependencies

## Overview

Define the pure dependency vocabulary and pre-read admission used by Kubernetes
capture. No network calls, credential discovery, experiments or mutations.

## Motivation

Capture must derive a complete bounded read plan from real Pod API shapes.
Keep this domain language separate from HTTP orchestration and within PR limits.

## Changes in Detail

Use one native-path registry for both collection and schema construction.
Collect ConfigMap and Secret references across regular, init and ephemeral
containers, volumes, projections and image-pull credentials.
Required uses win when references are combined; ordering is deterministic.
Key selectors require their data key. Only selectors with native optional
semantics may make a dependency optional.

Admission rejects unknown environment selectors, projected dependencies and volume
types outside the capture model, including PVC-backed volumes.
This is not a generic Kubernetes resource validator or a live fingerprint.
The dependent acquisition implementation remains a separate PR.

## Testing Plan

Use API-shaped Pod fixtures with ListMeta, selector data keys and immutable image
identities. Test every registered path, optional/required correlation, ordering,
native array shapes and malformed selector admission.
Run both OPSV product contexts, isolated native package tests and normal signed hooks.

## Deployment Plan

No deployment, configuration or authority change. No cluster calls.

## Related Issues/PRs

N7.EX.1; captured decoder #2336; shared HTTP ingress #2340; settings #2341.
API contract: <https://kubernetes.io/docs/reference/kubernetes-api/core/pod-v1/>.

## Checklist

- [ ] Adversarial and applicable standards review
- [ ] Product, native and normal signed checks
- [ ] Fresh full Copilot review and all CI
