<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: capture Kubernetes environment identity for OPSV evidence

## Overview

Decode captured Kubernetes observations into a stable, drift-sensitive OPSV
environment fingerprint. This is an observation contract, not experiment authority.

## Motivation

N7 requires cluster, node-pool, image and configuration provenance. Resource
names alone cannot distinguish replacement resources or changed deployment state.

## Changes in Detail

- Admit producer-shaped Kubernetes JSON observations at the external boundary.
- Preserve physical resource identity and immutable running image digests.
- Hash configuration canonically without placing secret values in evidence.
- Reject incomplete, ambiguous or inconsistent snapshots instead of guessing.

Secret projections use the observed server revision, never a digest of secret
values or annotations. Read fixtures retain the API's base64 `data` shape.
Resource identities include UIDs; desired configuration excludes API status,
resource versions and timestamp bookkeeping for non-Secret objects.

## Testing Plan

Use captured-shape fixtures only, with no cluster access. Verify drift sensitivity,
stable ordering, malformed observations and sanitized diagnostics. Run affected
product tests, packaged acceptance, adversarial standards review and normal gates.

The public decoder, projections, schemas and assembly pass 18 native source tests
and 73 assertions. The contract requires explicit cluster and node-pool selection,
physical resource UIDs and immutable image IDs. Shared factories retain native
Kubernetes string keys. Regressions cover inconsistent identities, missing or
mismatched container status, image tags and sanitized unsupported content.

Repeated logical or physical identities must agree on fingerprint material,
including observed images and node runtime. Configuration admits typed v1
ConfigMap and Secret observations only. Runtime Pods and Nodes belong exclusively
in their dedicated collections, where placement, image completeness and runtime
evidence are enforced. Unknown kinds and versions are rejected.
Workload-controller schemas are not claimed by this decoder.
The review regressions first reproduced six failures without errors.
The collection-role regressions separately reproduced four failures and one error.

The decoder does not establish capture freshness, authenticity or completeness
against a live cluster. Read-only acquisition must bind target selection and
configuration-reference closure before experiments can use this evidence.
This slice alone does not complete N7.EX.1.

## Deployment Plan

No cluster mutations, credential discovery or live experiments. The later
read-only acquisition adapter must supply these observations for explicit targets.
Staging and recommend-only remain the backend defaults.

## Related Issues/PRs

N7.EX.1 and N6 environment provenance. Independent of the Prometheus client.

## Checklist

- [ ] Fingerprint contract and producer-shaped regressions
- [ ] Product and packaged acceptance
- [ ] Adversarial standards review and normal signed hooks
- [ ] Fresh Copilot review and all CI gates
