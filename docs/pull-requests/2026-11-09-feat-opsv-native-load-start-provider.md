<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): execute exact owned-load gate release

## Overview

Implement the native conditional gate-release provider after current retained
recovery readiness and unchanged owned-worker reconstruction. Construction and
execution remain separate; no live experiment is authorized by this change.

## Motivation

N7 §5.5 requires separately authorized forward load effects. Inert Pod creation
and recovery approval do not authorize starting traffic.

## Changes in Detail

Reuse the exact start constructor, closed provider ingress, bounded owned HTTP
dispatch and independent six-argument commit pin. Reconstruct the current intent
before the single conditional patch. An acknowledgement confirms gate removal
only, not traffic, SLOs or recovery. Gate absence or an empty gate vector must be
observed on an identity-matched native Pod; malformed or foreign receipts remain
uncertain. Creation/deletion success statuses do not become patch success.

Share closed provider schema factories and canonical reconstruction with stop
and cleanup. Preserve their existing operation, method, media and body contracts.
Rename the recovery-only ingress to reflect its actual owned-load role; retain
no obsolete aliases. Existing independently issued recovery authority remains
separate from forward issuance and cancellation behavior.

## Testing Plan

Use real retained approval, creation, forward root issuance, durable transactions
and live ownership reconstruction; simulate HTTP only. Cover stale revisions,
changed execution, revoked grants, independent pins, uncertain responses,
bounded future cancellation and synchronous dispatch ownership.

- Strict lint: zero warnings/errors; every checked namespace has at most three inferred strata.
- Polylith structure, both consuming JVM products and task classpaths pass.
- Source and isolated fresh package: 127 tests / 3,860 assertions each.
- Curated OPSV/workflow/artifact integration: 290 tests / 5,859 assertions.
- Native compatibility: 308 tests / 1,903 assertions across 42 suites.
- Thirty-two faults each trigger assertion failures with zero errors.
  These include six native-start, ten start-constructor, seven cleanup and nine
  native-cleanup faults. They cover exact scope, canonical reconstruction,
  execution profile, native receipts, status policies and synchronous ownership.

## Deployment Plan

Off by default. No cluster mutation, image pull, load or cleanup is authorized.
Runner-side current N4/N8 effect admission and independent exact authority remain
required before execution.

## Related Issues/PRs

Base: main. Depends on the exact owned-load start constructor; publish only after
that prerequisite has merged and the combined implementation is validated.

## Checklist

- [x] Prerequisite merged and native provider implemented.
- [x] Source, JVM, isolated package and fault-injection validation.
- [x] Owner adversarial and full applicable standards review.
- [ ] Normal signed commits, current full review, CI and owner merge.
