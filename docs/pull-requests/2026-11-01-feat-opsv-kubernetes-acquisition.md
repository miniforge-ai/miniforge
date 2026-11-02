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

Explicit target settings require a named cluster, namespace, Pod selector and
node-pool label. Only HTTPS API origins are admitted; no credential discovery.
Timeout, byte and resource bounds load from schema-checked EDN defaults.
Prometheus and Kubernetes share the HTTP wire schemas rather than copying them.

The public read-only capture binds every returned object to its requested kind,
namespace and name. It derives Node and ConfigMap/Secret reads only after admitting
the complete target Pod list. Required absence, pagination, malformed dependency
paths, foreign namespaces and resource-budget exhaustion fail closed.

Unknown volume types and projected dependencies outside this capture model are
rejected, including PVC-backed volumes. No incomplete fingerprint is returned.
All reads share a capture deadline and owned HTTP cleanup. This is not an atomic
cluster snapshot and does not run an experiment, create grants or mutate resources.

Captured decoder #2336, Prometheus transport #2314, bounded reader #2339 and shared
HTTP ingress #2340 and settings foundation #2341 are merged.
Pure dependency foundation #2343 must merge before this PR.

## Testing Plan

Use native API-shaped fixtures only. Exercise all container groups, reference
paths, duplicate use and optional-versus-required correlation. Transport tests
substitute the effect boundary and never contact a cluster.
Settings tests cover target requirements, endpoint and namespace rejection,
sanitized configuration failures, and preservation of fatal/interrupted causes.
Public capture tests use real API-shaped JSON, including ListMeta and container
references. JVM tests use actual JDK response/stream types. After integrating the
dependency floor, both products, 22 JVM tests (70 assertions) and 92 isolated
native-package tests (472 assertions) passed, with normal signed hooks.

## Deployment Plan

Implementation only. Preserve explicit targets and staging/recommend-only defaults.
No cluster calls, mutations, credential discovery or live experiments are authorized.

## Related Issues/PRs

N7.EX.1; captured observation decoder PR #2336; Prometheus transport PR #2314.
API reference: <https://kubernetes.io/docs/reference/kubernetes-api/core/pod-v1/>.

## Checklist

- [x] Pure configuration-reference discovery
- [x] Bounded read-only acquisition and public boundary
- [x] Product and packaged acceptance
- [x] Adversarial standards review and normal signed hooks
- [ ] Fresh full Copilot review and all CI gates
