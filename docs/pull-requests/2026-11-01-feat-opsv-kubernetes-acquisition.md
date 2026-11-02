<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: acquire explicit Kubernetes observations for OPSV

## Overview

Acquire read-only observations for an explicit target and pass them through the
captured fingerprint contract. No live queries or experiments during development.

## Motivation

An externally supplied snapshot cannot establish target binding, freshness or
configuration coverage. The backend must collect the referenced resources itself.

## Changes in Detail

Start with pure discovery of Pod ConfigMap and Secret references. Source paths
follow the Kubernetes Pod API, including environment variables, projected volumes,
image pull credentials and inline CSI credentials. Optional references remain
explicit; any required use makes the combined resource reference required.

Acquisition, bounded transport and target admission are not implemented yet.
This work depends on the captured decoder in PR #2336 before backend integration.

## Testing Plan

Use native API-shaped fixtures only. Exercise all container groups, reference
paths, duplicate use and optional-versus-required correlation. Later transport
tests must substitute the effect boundary and never contact a cluster.

## Deployment Plan

Implementation only. Preserve explicit targets and staging/recommend-only defaults.
No cluster calls, mutations, credential discovery or live experiments are authorized.

## Related Issues/PRs

N7.EX.1; captured observation decoder PR #2336; Prometheus transport PR #2314.
API reference: <https://kubernetes.io/docs/reference/kubernetes-api/core/pod-v1/>.

## Checklist

- [ ] Pure configuration-reference discovery
- [ ] Bounded read-only acquisition and public boundary
- [ ] Product and packaged acceptance
- [ ] Adversarial standards review and normal signed hooks
- [ ] Fresh full Copilot review and all CI gates
