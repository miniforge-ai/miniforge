<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): independently govern native load compensation

## Overview

API surface class 1: trusted-host stop and cleanup of proven experiment-owned
load resources using separate current recovery authority.

## Motivation

N7 §5.5 requires bounded load compensation after every trial exit, even when
forward authority is revoked. An acknowledged stop or delete is not proof of
termination or resource absence.

## Changes in Detail

Reuse the independently governed recovery
lifecycle, exact per-plan operation identities, fresh ownership and quiescence
constructors, conditional native providers and acknowledged N3/N6 evidence.
Recovery must preserve the original control binding and keep forward effects
fenced. Cleanup must not delete a running or unproven resource.
Separate runtime-owned operation permission and an exact current N4 effect
decision precede issuance and are checked again at dispatch. Plan approval alone
cannot authorize either effect. Reuse canonical request factories to bind fresh
UID/resource-version preconditions without copying native payloads.
Share physical-root-bound reads with resource absence observation, and share
fixed recovery-slot matching with baseline restoration. Code-owned operation
factories select constructors, admission, exact authority and native dispatch.

## Testing Plan

Compose real plan retention, native ownership, grant issuance, transactions,
journal and evidence with simulated HTTP. Cover revoked forward authority,
current policy, exact live UID/revision, quiescence, replay, uncertainty and
publication failures. Run strict design checks, affected JVM products and fresh
source/isolated-package validation under the serial protocol.
The initial native source and isolated-package matrix passed 196 tests /
6,420 assertions and 27 meaningful faults per mode.
After integrating the reviewed exact-effect admission prerequisite, both
consuming JVM products, structure, classpaths, strict lint, inferred strata and
a fresh CLI passed again. Current source and isolated package each passed
35 affected tests / 1,604 assertions and 20 meaningful faults.
These cover all current load execution, baseline restoration and changed absence
consumers. Unchanged lower native consumers retain the earlier broad proof.
Every fault produced assertion failures and zero errors.
Public permission documentation and default running-path coverage were added
after that matrix. Both real running-workflow cases passed source and unchanged
isolated-package validation, with 44 assertions per mode.

## Deployment Plan

No live effects, traffic, credentials, cluster access or experiment activation.

## Related Issues/PRs

Builds on merged #2498, independently governed baseline restoration. Merged main
is integrated without changing the validated implementation or test tree.

## Checklist

- [x] Implement exact independently authorized stop and cleanup.
- [x] Validate source, JVM products, package and meaningful guard-removal checks.
- [x] Complete root adversarial and applicable standards review.
- [ ] Signed commits, current-head full review, all CI and owner merge.
