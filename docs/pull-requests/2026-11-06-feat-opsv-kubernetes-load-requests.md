<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Construct bounded native Kubernetes load requests

## Overview

Construct canonical, scheduling-gated staging load-Pod proposals without dispatching them.
Base: main. Layer: native request data. Depends on merged #2420 and #2440.
The independent stopped-recovery issuer fix is tracked separately in #2441.

## Motivation

The native backend needs bounded load generation in addition to replica changes.
An actuation mode, image or Pod manifest is not permission to send traffic.
This layer supplies exact payloads for later N4/N8/N10 admission and execution.

## Changes in Detail

Use a fixed Fortio HTTP profile with explicit operator-supplied image digest and
resource limits. Bind the workflow, attempt, exact target, Pod name, traffic
destination and payload hash through the existing dedicated load-test scope.
Keep TLS verification, disable redirects and retries, and bound request count,
concurrency, rate and active worker duration. No arbitrary command, shell, mounts or credential configuration.
Reuse canonical proposal construction rather than duplicating the replica producer's maps.

The generator contract is checked against
[Fortio's official CLI documentation](https://github.com/fortio/fortio#command-line-arguments).
No default image or live backend activation is introduced.

The adversarial pass rejected Jobs because Kubernetes can start the same program twice,
even with one completion and `restartPolicy: Never`.
Use a bare Pod with no owning controller and an initial scheduling gate instead.
Creation cannot start traffic through the normal scheduler.
A later separately authorized release needs exact Pod UID/resource-version preconditions;
neither a creation acknowledgment nor this constructor proves execution or termination.
The active deadline does not bound time spent behind the scheduling gate.
The runtime still needs a wall-clock attempt budget and separately authorized cleanup of pending Pods.
See [Kubernetes Job failure semantics](https://kubernetes.io/docs/concepts/workloads/controllers/job/#handling-pod-and-container-failures)
and [scheduling readiness](https://kubernetes.io/docs/concepts/scheduling-eviction/pod-scheduling-readiness/).

## Testing Plan

Exercise canonical native request data, actual retained-original acquisition,
exact grant scope and changed-payload refusal using offline fixtures.
Test malformed input, unbounded settings, foreign targets and unsupported durations.
Run strict lint, genuine inferred strata, consuming products, curated offline
integration and isolated fresh-package checks. Serialize JVM/build/hook runs.

Passed: strict lint with zero warnings/errors, all 15 changed namespaces at
three or fewer inferred strata, Polylith check and both consuming products.
The curated integration suite passed 152 tests / 1,767 assertions.
The isolated fresh CLI package passed 32 tests / 371 assertions and the
native compatibility suite passed 308 tests / 1,903 assertions, without production source overlays.
All nine injected faults produced assertion failures with zero test errors.
The JVM gate caught a `java.lang.Runtime` name collision; the schema is now
`LoadWorkerSettings`, and the complete validation passed afterward.

Review found that Fortio expands `{uuid}` in decoded URL paths.
Reject that token before proposal construction, including mixed-case percent encoding and repeated tokens.
Three new regressions failed against the original implementation with zero test errors.

## Deployment Plan

Data construction only. Provider dispatch, retained approved recovery plans,
fresh grants, resource ownership, cleanup and verified termination remain separate gates.
No cluster writes, load traffic, image downloads or credential changes.

## Related Issues/PRs

N7 §5.5 and §6, N10 §1.5; #2420 governed proposals; #2441 stopped recovery issuance.

## Checklist

- [x] Implement and adversarially review the complete diff against standards.
- [x] Pass offline validation.
- [x] Pass normal signing and hooks.
- [ ] Settle current-head review, fresh full review and CI; owner-merge.
