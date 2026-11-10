<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): independently govern native baseline restoration

## Overview

API surface class 1: trusted-host execution of one exact baseline-restoration
effect, including after forward authority is revoked or the run is stopped.

## Motivation

N7 §5.5, N8 §3.4.2 and N10 §1.5 require independent current recovery authority.
An approved recovery plan and a provider callback cannot substitute for it.

## Changes in Detail

Share durable effect orchestration with the forward path while keeping recovery
admission, registration and dispatch separate. Preserve actual running, paused,
blocked, failed or cancelled status. Fence forward effects and refuse restoration
while forward mutations remain in flight. Read fresh provider preconditions
before issuing an exact restoration grant. Keep independent current N4 admission,
original-target binding and acknowledged N3/N6 lineage.

The public recovery-effect identity factory assigns one stable durable slot per
plan hash and recovery operation using printer-independent UTF-8 encoding.
A different caller attempt ID cannot create
another slot or retry an uncertain effect. Shared factories own readiness,
provider-runtime and operation descriptors; shared authorization preserves the
forward path's final stop check and uses independent recovery context.

One restoration acknowledgement is not verified baseline recovery or permission
for a new trial. Load termination, owned cleanup and final verification remain
separate obligations of the whole-trial host.
The host must also retain unresolved preflight failures, not just effect records.

## Testing Plan

Real retained-plan, baseline, decision, authority, transaction, journal and
evidence producers use simulated HTTP. Tests cover all five admitted workflow
statuses, real forward-grant revocation, safe mode, missing approval, fixed slots,
revocation, expiry, replay, uncertainty, publication refusal and in-flight fencing.
Source and rebuilt isolated package each passed 120 tests / 3,419 assertions
and 20 meaningful counterfactuals, all with positive assertion failures and zero errors.
Both consuming JVM products, structure, task classpaths, fresh CLI, strict lint
and true inferred strata passed under the shared serial protocol.
After a behavior-identical test-helper cleanup, the 11 recovery tests passed
again against source and the unchanged package, with 308 assertions per run.
Review identified caller printer bindings affecting recovery UUIDs. The encoding
now uses the closed hash and operation vocabulary directly. Both consuming JVM
products, strict lint, inferred strata, task classpaths and a fresh CLI passed
again. Source and rebuilt isolated package each passed the updated 12 recovery
tests / 321 assertions and eight meaningful faults, including the reproduced
printer-state regression. Every fault failed assertions with zero errors.

## Deployment Plan

No live effects, credentials, cluster changes, experiments or activation.

## Related Issues/PRs

Builds on merged #2497. Current main integration preserves the exact proven
production and test tree.

## Checklist

- [x] Implement independently authorized exact restoration.
- [x] Source, JVM, package and meaningful fault validation.
- [x] Root adversarial and full applicable standards review.
- [ ] Signed commits, current-head full review, all CI and owner merge.
