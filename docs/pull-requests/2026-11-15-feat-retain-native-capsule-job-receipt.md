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

Separate the existing transport polling and resource projection into
bounded namespaces. Preserve running and terminal readiness outcomes.
Put relocated operator messages in the runtime catalog.

## Testing plan

Mock the provider. Check the creation command and retained receipt.
Refuse missing and mismatched identity, changed deadline, malformed
output, and nonzero exit without readiness or cleanup calls.
Exercise the default, both levels of override, and invalid values; check
that a provider response reverting an override to the default is rejected.

Both JVM products, Babashka source, and the fresh package passed 6 tests
and 38 assertions each. The old acquisition path failed four receipt
assertions. Deliberately ignoring the task override failed its budget
assertion without a runtime error. Strict lint, namespace depths,
Polylith, and Babashka classpaths passed.

## Deployment plan

No deployment action or live Kubernetes tests. This change does not
verify Pod ownership, front-door authority, or successful containment.
Ordinary release and workload dispatch are unchanged.

## Related issues/PRs

PR #2528 defines full stop; PR #2534 defines shared controller identity.

[Pinned standards guide](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/agents.md)
and [catalog](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/index.mdc).
