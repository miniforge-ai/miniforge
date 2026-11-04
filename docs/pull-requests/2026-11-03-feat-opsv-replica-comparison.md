<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Predeclared replica population comparisons

## Overview

Extend the pinned comparison contract with explicit candidate and recovery
replica expectations. Replacement identities alone do not establish drift or
verification success.

## Motivation

Scaling needs to admit replacement replicas without hiding foreign workloads,
changed templates, images, placement, or unbounded population changes.

## Changes in Detail

- Pin separate candidate and recovery membership rules and count bounds.
- Require exact runtime target, template hash, images, and allowed node pools.
- Reject duplicate replica identities and missing population observations.
- Keep fingerprint comparison, scaling stability, and SLO evaluation separate.

The existing `phase-opsv` component owns these pure comparison rules.
Its public boundary validates data once; internal comparison stages trust it.
No native capture, controller-chain resolver, runner integration, or effects are
implemented by this PR. Acquisition must independently prove replica membership.

## Testing Plan

Test replacements, count limits, foreign membership, template/image/pool drift,
duplicate identities, missing observations, and independent recovery expectations.
Run both affected product contexts, native packaging, normal hooks, and review.

Focused source coverage passes: 17 tests, 75 assertions. Positive cases include
one-to-three replacement replicas, exact recovery membership, and explicitly
declared zero capacity. Negative cases cover all target identity fields,
template/image/pool drift, count bounds, duplicate identities, omitted population
observations, expanded expectations, and contract substitution.

## Deployment Plan

No live operation. This is the replica-comparison stratum below native projection
and durable guarded-runner admission. No changes to runtime execution authority.

## Related Issues/PRs

Depends on #2353 and #2354. The private branch starts from #2354's implementation
because its comparison contracts are required. Publish only after the parents
merge and reconcile against actual `main`, excluding inherited parent changes.

## Checklist

- [x] Pin positive and negative population cases through the public interface.
- [ ] Complete owner standards and adversarial review.
- [ ] Pass product, package, hook, CI, and full-head review gates.
