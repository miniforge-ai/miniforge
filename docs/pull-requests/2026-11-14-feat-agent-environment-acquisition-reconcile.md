<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Expiring acquisition claims and reconcile before resubmission

## Overview

Third PR for issue #2506. A reservation now records which controller is provisioning it, until when,
and a digest of its request. If that controller dies, or its response to the provider is lost, a
later acquire of the same request finds the claim expired. It asks the provider what exists for the
reserved generation and adopts that environment, or releases the reservation, before submitting
anything new. The same request is never provisioned twice, and a reservation nothing answers for no
longer blocks its key forever.

API surface class 1: in-process EDN.

## Layer

Domain (the lease rules) and Application (the controller and the lifecycle).

Base branch: `feat/agent-environment-ledger-file`. Depends on PR #2522 and PR #2521, both open.
Retarget to `main` once they merge.

## Motivation

After the first two PRs, a provision whose ledger record failed, or whose controller crashed between
claiming and recording, left the key in `:state/provisioning` with no way out. Every later acquire was
refused as in flight. Issue #2506's first acceptance test asks that replaying an acquisition after a
restart at any persist, submit or acknowledge boundary yields one authoritative environment, or that
an explicit unknown outcome is reconciled before another submission.

## Changes in Detail

1. `lease` (pure rules):
   1. `reserve` takes a claim (`:claim/controller-id`, `:claim/expires-at`) and stores
      `:entry/request-digest`, a content hash of the request with its expiry taken as an `Instant`.
   2. `acquire-decision` takes `now` and compares requests by digest, so the comparison holds across
      restarts and across a `Date` or `Instant` expiry.
   3. A provisioning entry whose claim has expired yields `:reconcile` instead of `acquire-in-flight`.
   4. `reconciled` builds the adopted (provisioned) or abandoned (released) entry.
2. `controller`:
   1. Each controller gets an id and a claim TTL: `:claim-ttl-ms`, defaulting to 15 minutes, the
      pilot's readiness target.
   2. `claim!` stamps the claim on a new reservation.
   3. `settle-reconciled!` applies a reconcile only while the stale entry is still the key's exact
      entry, so two controllers reconciling at once settle it once.
   4. `settle-provision!` now merges onto the reservation, keeping its digest.
3. `lifecycle.reconcile/reconcile!` observes the provider at the reserved generation:
   1. An object carrying that generation is adopted.
   2. Anything else, or nothing, releases the reservation.
   3. A provider that cannot answer leaves the reservation untouched and the acquire is refused with
      the provider's anomaly. "Cannot see it" is never treated as "nothing exists".
   4. Each reconcile is receipted under the new operation `:environment/reconcile`, with an
      `:step/adopted` or `:step/abandoned` step.
4. `lifecycle/acquire!` acts on the claim decision. After a reconcile it claims again and acts on the
   fresh decision once. The duplicate path returns the adopted environment, and the new path
   provisions the next generation.
5. `provisioning/adopted`: when the owner finally records its provision, another controller may
   already have reconciled the reservation after the claim expired. The owner checks that the entry
   it recorded holds its lease. If not, it deletes what it created by UID and returns `claim-lost`
   instead of handing out a lease nothing holds.

## State Names

Issue #2506 names the states acquiring, ready, releasing, unknown and released. This series keeps the
names the contract already has: `:state/provisioning` is acquiring, `:state/provisioned` is ready,
`:state/cleanup-uncertain` is the unknown cleanup, and `:state/released` is released. Releasing,
with cleanup requested, applied and verified recorded separately, is the next PR.

## Design Notes

The easy option was to reconcile any provisioning entry on sight. It is rejected because the owner
may still be provisioning, and a reconciler that adopted a half-built environment, or released a
reservation whose objects were about to appear, would race it. The claim TTL gives the owner a
bounded window. Past it, the claim-lost guard keeps a late owner from leaking what it built.

Claims compare expiry times written by one controller against another controller's clock, so clock
skew between controllers shortens or lengthens the window by the skew. The TTL must exceed the
slowest provision plus its cleanup, plus that skew.

A request that conflicts with a stuck reservation's digest is refused as conflicting and does not
trigger a reconcile. A sweep that reconciles expired claims without waiting for a replay is later
work.

## Testing Plan

1. `lease_test`:
   1. A `Date` expiry for the same moment is a duplicate.
   2. An expired claim yields `:reconcile`; a provisioned entry past the claim's end is still a
      duplicate.
   3. `reconciled` adopts or releases.
2. `reconcile_test`, over a counted fake provider, a fault-injecting ledger and a controller whose
   clock is past every claim:
   1. A lost provision response, replayed: one `provision!` call in all, the environment adopted and
      ready, one `:step/adopted` reconcile receipt.
   2. A claim recorded just before a crash, so no provider call was made: the reservation is released
      and generation 2 provisioned, with one `provision!` call in all.
   3. An unexpired claim is left alone: zero `observe` and zero `provision!` calls, refused as in
      flight.
   4. A provider that cannot answer: refused with its anomaly, the reservation still provisioning,
      zero `provision!` calls.
   5. An owner whose reservation was abandoned before it recorded gets `claim-lost`. Its namespace is
      deleted, and the ledger shows the reservation released. Without the guard, this test fails with
      a returned lease and a namespace left running.
3. The agent-environment suite passes: 82 tests with 301 assertions. Agent-worker and opt-in profile
   pass: 9 tests with 42 assertions. clj-kondo and stratum-lint are clean.

All of this is fake-provider evidence. A real provider's `observe` must be authoritative for the
generation it reports, and that is not shown here. Receipts stay `:profile/unqualified`.

## Deployment Plan

Nothing deploys. The component is opt-in.

## Related Issues/PRs

Issue #2506; parent tracker issue #2439. Stacked on PR #2522, which is stacked on PR #2521.

## Checklist

- [x] Claims, digests, reconcile and the claim-lost guard, with tests
- [ ] PRs #2521 and #2522 merged; retarget to `main`
- [ ] CI green, Copilot threads resolved, squash-merged
