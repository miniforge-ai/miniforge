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

## Authorization review

Recovery after forward revocation implements the operator-approved N7 §5.5 and
N10 §1.5 contract, not an exception to those gates. The opaque original control,
authority directory, current-context reader and fence are trusted host values;
the closed serialized input cannot replace them. Recovery closes forward
admission and requires settled effects. It obtains a new exact N4 decision and
grant outside the stopped forward bag. It rechecks current operation permission,
native ownership, payload preconditions and grant validity at dispatch.
Plan approval alone and revoked forward authority grant no recovery permission.

Root traced both native operations through the actual issuer, durable claim and
provider dispatch. Real denial tests prove zero issuance before permission or
fresh-payload admission, and zero mutation after late permission changes.
Counterfactual removal of each admission guard produces assertion failures, not
errors. Private authority-directory mismatch, unproven ownership, physical-root
drift and arbitrary operation identities also refuse. No live authorization is
inferred from this review.

Main integration preserved the signed implementation and test tree. The
phase-opsv tree at both `969c24e7` and `0e18ae04` is
`afe4fff0c0fd54631bb533a1b4e0bb6cfd2fe190`; review clarifications do not change it.
The overview-review follow-up rebuilt the CLI at `0e18ae04`.
Five focused real authority tests / 356 assertions passed in both source and the isolated package.
No production-source overlay was used. This covers revoked forward grants,
separate exact recovery permission, ownership/directory/slot binding and fresh
pre-issuance payload preconditions. The tested package SHA-256 is
`4776adeaf8738fe38534cf61e53871a049eaccde5cf01f2bce7dec6b9e7378dc`.

## Related Issues/PRs

Builds on merged #2498, independently governed baseline restoration. Merged main
is integrated without changing the validated implementation or test tree.

## Checklist

- [x] Implement exact independently authorized stop and cleanup.
- [x] Validate source, JVM products, package and meaningful guard-removal checks.
- [x] Complete root adversarial and applicable standards review.
- [ ] Signed commits, current-head full review, all CI and owner merge.
