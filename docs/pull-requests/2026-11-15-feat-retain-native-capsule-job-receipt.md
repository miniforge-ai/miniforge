<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: retain the exact native capsule Job creation receipt

## Overview

Submit a capsule Job by create-only request. Admit its readiness path
only after the server confirms the requested identity and deadline.

## Motivation

A mutable Job name is not evidence that this executor created a capsule.
N11 §4.4 requires immutable identity before containment decisions.

## Changes in detail

Runtime boundary. Never overwrite an existing Job through acquisition.
Retain the returned UID and creation resource version in the environment
record. The creation version is historical, not a fresh precondition.
An unconfirmed request must not trigger readiness checks or name-only
cleanup. The Job deadline uses N11 §5.3's packaged 3600-second default.
Executor configuration can set `:resources/timeout-seconds`; a task can
override it under `:resources`. Explicit nonpositive or noninteger values
are rejected before submission, not replaced with the default. The receipt
must confirm the effective deadline, including any override.
The create-request timeout and Pod readiness timings are independent
executor options with validated packaged defaults; changing either
does not change the Job deadline.
Readiness shares one monotonic deadline across Pod listing, status reads,
output capture and polling waits. Expiry stops the owned CLI process and
rejects late status results; process cleanup has a bounded shutdown grace.
Unconfirmed requests retain allowlisted reason codes and an available
process exit code. Provider output, environment values and exception
messages are never copied into these diagnostics. Status hints follow
[kubectl's error formatter](https://github.com/kubernetes/kubectl/blob/master/pkg/cmd/util/helpers.go#L150-L244).

Separate the existing transport polling and resource projection into
bounded namespaces. Preserve running and terminal readiness outcomes.
Put relocated operator messages in the runtime catalog.

## Testing plan

Mock the provider. Check the creation command and retained receipt.
Refuse missing and mismatched identity, changed deadline, malformed
output, and nonzero exit without readiness or cleanup calls.
Exercise the default, both levels of override, and invalid values; check
that a provider response reverting an override to the default is rejected.
Verify the create-request and readiness options reach their own boundaries
without changing the capsule budget.
Use disposable CLI fixtures to hang either readiness read and select a
polling interval longer than the total budget. Refuse a late Running
reply and preserve cancellation both before and during polling.

Both JVM products, Babashka source, and the fresh package passed the
focused packet: 21 tests / 119 assertions each. The old acquisition path failed four receipt
assertions. Ignoring an override failed its budget assertion; dropping the
diagnostic data failed eight assertions. Neither introduced runtime
errors. Tests also confirm that an interrupted submission preserves the
interrupt flag. Strict lint, namespace depths, Polylith, and Babashka
classpaths passed.

## Deployment plan

No deployment action or live Kubernetes tests. This change does not
verify Pod ownership, front-door authority, or successful containment.
Ordinary release and workload dispatch are unchanged.

## Related issues/PRs

PR #2528 defines full stop; PR #2534 defines shared controller identity.
PR #2542 supplies the bounded command prerequisite and lands first.

[Pinned standards guide](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/agents.md)
and [catalog](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/index.mdc).
