<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): prepare fresh original-bound native replica trials

## Overview

API surface class 1: read-only construction of exact bounded trial requests from
the independently retained plan, original baseline and fresh native observations.

## Motivation

N7 temporary trials need current physical identity and conditional native payloads.
Caller-provided snapshots and a bounded replica count alone are not that proof.

## Changes in Detail

Re-admit the retained recovery plan and original, verify fresh original
configuration and membership, then use
the existing canonical replica constructor with current UID/resource version.
Share plan-target admission and proposal-input construction with restoration.

## Testing Plan

Real retained plan/original and native projection/constructors with simulated
HTTP only. Cover exact conditional requests, bounded counts, replica replacement,
candidate substitution, unrelated drift, foreign targets and caller extensions.
Preserve recovery-verifier/restoration behavior. Validate source/package behavior,
meaningful faults, both JVM products, strict strata/lint, structure and packaging
under the shared serial protocol.

Both consuming JVM products, strict lint (zero warnings/errors), true inferred
strata, Polylith structure, task classpaths and a fresh CLI package pass.
Source and isolated package each pass 42 trial/recovery/restoration tests / 1,693
assertions, plus the existing runtime group at 47 tests / 629 assertions.
All 32 relevant faults in each environment produce assertion failures with zero errors.

## Deployment Plan

No grant issuance, activation, live trials, mutation or cluster changes.
Constructing an intent is neither permission to execute it nor verification success.

## Related Issues/PRs

Depends on retained-baseline recovery verification before publication.
The shared forward-runtime prerequisite #2494 is merged.
The published branch will use current merged main; this is a local dependent draft.

## Checklist

- [x] Implement fresh original-bound trial construction and shared pure factories.
- [x] Source, JVM, package and fault validation.
- [x] Owner adversarial and full applicable standards review.
- [ ] Signed commits, current-head full review, all CI and owner merge.
