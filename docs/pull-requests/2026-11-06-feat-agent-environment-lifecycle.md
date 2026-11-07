<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Agent-environment lifecycle: acquire, readiness and release over a fake provider

## Overview

Third of four stacked PRs for the issue #2439 environment contract (pilot slice D1): the lifecycle
that drives any environment provider through the ledger controller, an in-memory fake provider, and
the public interface for it. API surface class 1: in-process EDN. Every call emits a receipt marked
`:profile/unqualified`.

## Layer

Application. Depends on the controller PR (`feat/agent-environment-controller`). The last PR adds
exec, file copy, artifact persistence, cancel and the shared provider conformance suite.

## Motivation

Issue #2439 requires that duplicate acquisition returns one environment; that a conflicting
request, a forged tenant and a stale lease are refused; and that partial provisioning produces
explicit receipts and bounded cleanup. Those decisions must be made once, in miniforge, not in each
provider: providers supply compute and report what they did.

## Changes in Detail

1. `lifecycle`:
   1. `acquire!`: identity, expiry and grant admission, then declared-capability and live-probe
      preflight, then the ledger decision. A duplicate returns the same environment; a conflicting
      request is refused.
   2. `readiness`: refused when the namespace now carries another UID than the lease.
   3. `release!`: verified only when every delete is confirmed; otherwise the key stays
      `cleanup-uncertain` and a later release with the same lease retries.
   4. `preflight`: identity plus capability and probe failures, receipted.
2. `lifecycle.provisioning`: a provision that stops part way is receipted as partial and cleaned
   up at once; the cleanup is receipted as a release. A provider that reported no UID is asked
   for it (`observe`) when any step applied or is uncertain. The UID is adopted only from an
   object carrying the reserved lease generation, so a namespace someone else recreated under the
   name is neither leased nor deleted. Without an adopted UID nothing is deleted by name, and the
   key stays `cleanup-uncertain`.
3. `validation` and `interface`: boundary schemas for every call; invalid input returns an
   `:invalid-input` anomaly and emits no receipt.
4. `fake`: in-memory provider with fault options (failed step, unconfirmed deletes, probe
   failures, omitted UID, uncertain steps, a namespace recreated right after provisioning, replaced
   UID). It records the generation it provisions and `observe` reports it.
5. `conformance.fixtures`: synthetic tenants, grants and requests shared by these tests and, next,
   by the provider conformance suite.

## Testing Plan

`lifecycle_test` drives the public interface over the fake: duplicate acquire, conflicting request,
forged tenant (request and lease), missing grant, stale lease after release and reacquire, partial
provisioning with cleanup, unconfirmed delete with retry, recreated namespace, a provider that
omits the UID (recovered, or not adopted when the namespace was recreated, or recovered after
uncertain steps), missing isolation, and invalid input (no receipt). `clj-kondo`, stratum-lint and `poly check` clean.

## Deployment Plan

Nothing deploys. The ledger is in memory; durable leases and restart reconciliation are later work
(a later step of issue #2439).

## Related Issues/PRs

Issue #2439. Depends on the controller PR, which depends on #2445.

## Checklist

- [x] Controller, lifecycle and fake provider with tests
- [ ] CI green, Copilot threads resolved, squash-merged after the controller PR
