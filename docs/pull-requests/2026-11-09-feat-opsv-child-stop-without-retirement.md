<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): stop a child without retiring its control

## Overview

API surface class 1: stop one host-owned run, revoke its tracked forward grants,
and retain its opaque control registration.

## Motivation

N7 recovery must fence forward effects without authorizing another attempt.
Retirement can forget a settled control; it is not a substitute for stopping a
failed trial while recovery remains unresolved. Sibling runs need not stop.

## Changes in Detail

Reuse the existing stop, abort confirmation and
grant revocation vocabulary without forgetting the child or reopening fences.
Only explicit retirement can release the registration after the host verifies
recovery. This operation grants no recovery or provider authority.
Validate the opaque handle and clock at the public interface; the internal
stop operation trusts those admitted inputs.

## Testing Plan

Prove actual stored-grant revocation, sibling isolation, closed admission,
retained registration, repeated cleanup and explicit retirement. Reuse shared
control fixtures. Validate consuming products, source and package serially.
Both consuming JVM products, structure, classpaths, strict lint, inferred strata
and a fresh CLI build passed. Source and isolated package each passed 15 tests /
95 assertions and four meaningful faults. Every fault produced assertion
failures and zero errors.
The standards pass extracted shared control fixtures and the finite timing guard.
The existing asynchronous guard increased from two to five seconds; test logic
still synchronizes through explicit latches rather than scheduling delays.
After this test-only cleanup, source and unchanged isolated package each passed
all 15 tests / 95 assertions again. Production implementation was unchanged.
After moving the input guard to the interface, both consuming JVM products,
strict lint, structure, classpaths and a fresh CLI build passed again. Source
and isolated package each passed 15 tests / 95 assertions and four meaningful
faults with assertion failures and no errors.

## Deployment Plan

No live effects, credentials, cluster access or experiment activation.

## Related Issues/PRs

Independent prerequisite for the N7 native trial coordinator; no dependency on
the pending Kubernetes recovery PR chain.

## Checklist

- [x] Implement stop-without-forget and actual control tests.
- [x] Validate source, consuming products, package and meaningful faults.
- [x] Complete root adversarial and applicable standards review.
- [ ] Signed commits, current-head full review, all CI and owner merge.
