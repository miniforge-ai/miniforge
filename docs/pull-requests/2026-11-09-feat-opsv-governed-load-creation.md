<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): govern native inert load creation

## Overview

API surface class 1: trusted-process EDN composition of exact scheduling-gated
Pod creation with current N4/N8 checks, independent authority and durable audit.

## Motivation

N7 §5.5 requires runtime governance before the first temporary effect.
Provider callbacks alone do not supply this host composition.

## Changes in Detail

Use the shared governed forward-runtime prerequisite without duplicating its
admission, transaction, issuance, audit or cleanup mechanisms. Add creation-specific
closed input admission, retained-plan/payload binding and physical-target reads.
The derived load effect ID must match the independently retained recovery plan.
Recheck physical cluster/namespace UIDs before dispatch and retain only those identities.

Creation retains the provider-assigned UID and leaves the scheduling gate intact.
It does not release traffic, authorize recovery or prove experiment success.
Code-owned operation descriptions reference named functions; serialized input
cannot supply handlers, a decision, a grant or an allow flag.

## Testing Plan

Actual retained plan, original, root authority, transaction, N4, N8 and audit
producers with simulated HTTP. Cover default-off behavior, exact planned payload,
foreign physical targets, late policy/stop/revocation, replay and uncertainty.
Validate strict lint/true strata, both consuming JVM products, source/package
behavior and meaningful fault checks under the shared serial protocol.
The split feature passes both consuming JVM products, structure, task classpaths,
strict lint (zero warnings/errors), true inferred strata and a fresh CLI build.
Source and isolated package each pass 54 tests / 890 assertions and eleven relevant
fault checks. Every fault produces assertion failures with zero test errors.
The package contains no deleted load-start implementation or runtime-schema namespaces.

## Deployment Plan

No default activation, live experiments, image pulls, traffic or cluster changes.

## Related Issues/PRs

Base: main after shared forward-runtime prerequisite #2494.
Baseline recovery verification is a separate proof boundary.

## Checklist

- [x] Implement creation through the shared governed native host composition.
- [x] Current source, JVM, isolated package and fault validation.
- [x] Owner adversarial and full applicable standards review.
- [ ] Signed commits, current-head full review, all CI and owner merge.
