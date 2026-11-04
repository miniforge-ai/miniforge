<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Derive replica observations from captured Kubernetes workloads

## Overview

Derive comparison observations from captured Deployment-owned Pods, complete
namespace inventory and captured Nodes. Preserve raw and semantic observations.

## Motivation

N7 permits bounded replica replacement only when ownership, configuration,
allowed node pools and runtime image digests are proven independently. An
adapter's labels or assertion of intentional change are not sufficient.

## Changes in Detail

Base branch: main. Volume projection #2363 and evidence protection #2364 are
merged prerequisites. Private prerequisite history was reconciled before publication.

Reuse the complete inventory partition, native image correlation, service-account
volume projection and replica comparison vocabulary. The fixed, versioned
configuration profile retains Pod configuration and desired metadata. Placement
is checked separately against captured Nodes, never inferred from a pool claim.

Profile `:kubernetes/deployment-replica-configuration-v1` uses the existing desired
resource view, retaining labels and annotations while excluding status and
bookkeeping metadata. Only `nodeName` is removed from PodSpec configuration;
captured Node correlation and the declared pool contract cover placement.
The configured pool-label key is bound into this projection and its hash. It
cannot change interpretation after observation while preserving the same match.
The versioned service-account projection retains all remaining PodSpec content.
Image digests come from complete matching container status records, not image
tags or template labels. Raw input and per-UID configuration views remain visible.
Producers must protect sensitive reporting values before storing those views.

Refuse incomplete or ambiguous observations. Pending Pods remain visible in raw
inventory but cannot establish a complete runtime observation. Empty populations
remain distinct from missing data. No membership selection by label is added.

This is a pure captured-data projection. It does not prove freshness, template
approval, SLO health, scaling stability or execution authority. Consumers still
need a predeclared comparison contract and independent outside-resource drift
checks. No wildcard exclusions, native requests or experiments are introduced.

## Testing Plan

Use native-shaped captured fixtures. Prove replacement identities can retain
configuration while configuration, image and pool changes remain observable.
Refuse foreign owners, mismatched image status, unresolved placement and partial
inventories. Run product, isolated package, mutation, standards, signing, CI and
full-review gates before merge.

## Deployment Plan

Explicit pure API only. Live preparation and effect wiring remain separate.

## Related Issues/PRs

N7 section 6.3; merged inventory partition #2362, volume projection #2363 and
evidence protection #2364.

## Checklist

- [x] Native ownership, configuration, image and placement correlations proved.
- [x] Raw observations and versioned semantic projection retained separately.
- [ ] Local, owner, CI and full-review gates pass.
