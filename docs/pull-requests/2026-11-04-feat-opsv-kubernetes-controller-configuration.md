<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Preserve owned configuration behind bounded controller counts

## Overview

Project owned Deployment and ReplicaSet desired configuration after checking
their desired replica counts. Preserve original counts and raw input separately.

## Motivation

Runtime replica counts alone cannot admit controller configuration changes.
Scaling can change desired counts, but unrelated template, strategy, ownership
and lifecycle changes must remain visible to native verification.

## Changes in Detail

Base branch: main. Verification capture #2366 and outside invariants are private
prerequisites until merged. Reconcile that history before publication.

Reuse native inventory partition and the existing count-bounds vocabulary.
Require explicit nonnegative native counts. Check the Deployment count and total
owned ReplicaSet desired count against the supplied bounds independently.
Only then remove replicas from each owned controller spec before hashing.
Retain every other desired field, physical identity, ownership and lifecycle field.
Keep controller counts by UID beside the fixed, versioned configuration view.

Share the resource-profile factory with outside-resource projection. Preserve
that projection's existing behavior instead of duplicating its record shape.
Extract count fields without changing the existing replica contract wire format.

This API does not prove that bounds were predeclared. Its caller must derive
them from the retained contract for this exact target and attempt before effects.
It grants no authority, accepts no missing-count defaults and makes no runtime,
freshness, scaling-stability or SLO-health claim. Outside invariants and runtime
replica observations still require separate comparison. No storage or I/O occurs.

## Testing Plan

Cover both independent bounds, explicit zero versus missing counts, malformed
native values, controller ownership, other desired fields and unrelated resources.
Check the shared profile against existing outside-resource tests.
Run product tests, isolated native packaging, mutation and complete PR gates.

## Deployment Plan

Explicit pure API. Complete native fingerprint and runner composition remain separate.

## Related Issues/PRs

N7 section 6.3; replica observations #2365 and verification capture #2366.

## Checklist

- [x] Preserve raw desired counts while bounding native count normalization.
- [ ] Complete owner, product, package, CI and full-review gates.
