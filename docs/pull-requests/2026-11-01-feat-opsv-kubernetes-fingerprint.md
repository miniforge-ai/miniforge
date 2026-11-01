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

Resource projections and captured-resource schemas pass 5 native source tests and
15 assertions. The snapshot contract requires explicit cluster and node-pool
selection, physical resource UIDs and immutable image IDs. Shared factories retain
native Kubernetes string keys across both test namespaces.
The complete snapshot boundary, fingerprint assembly and live acquisition remain
unfinished. No environment-fingerprint conformance claim is made by this slice.

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
