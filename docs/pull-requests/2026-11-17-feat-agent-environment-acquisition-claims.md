<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Acquisition claims and request digests for agent environments

## Overview

Fourth PR for issue #2506. A reservation now records which controller holds it and until when: a
claim with a TTL, 15 minutes by default. It also records a digest of its request, and acquire
compares requests by digest. Just before the provider call, the controller re-reads the
reservation and provisions only while its claim has not expired.

API surface class 1: in-process EDN.

## Layer

Domain (the lease rules: claim, digest, reserve) and application (the controller's claim and the
dispatch guard).

Base branch: `main`. PR #2539, which this was stacked on, is merged. PR #2524 (reconcile and
takeover) is rebuilt on top of this.

## Motivation

Once its review findings were fixed, PR #2524 had grown past the 600-line PR budget, so it is
split. This PR holds the part that does not depend on takeover. A reconciler needs the claim and
the digest to decide whether a reservation's owner is gone and whether a stored reservation answers
the same request. The guard stops a controller whose claim has expired from sending a provision.
Issue #2506 asks for submission identity and digests persisted before provider dispatch.

## Changes in Detail

1. `lease`:
   1. `claim`: `{:claim/controller-id :claim/expires-at}`.
   2. `default-claim-ttl-ms`: 15 minutes, the pilot readiness target in issue #2439.
   3. `request-digest`: the content hash of the request with its expiry taken as an Instant, so a
      `Date` and an `Instant` for one moment digest alike. It hashes through `ledger.printing`.
      `namespace-name` now does too. A caller's printer settings (`*print-level*`,
      `*print-length*` and the rest) cannot make two requests digest alike or one request digest two
      ways.
   4. `claim-expired?`.
   5. `reserve` takes the claim and writes it with the digest.
   6. `acquire-decision` compares digests instead of request maps.
2. `controller`:
   1. `create` gives each controller a random `:controller/id` and takes `:claim-ttl-ms`.
   2. `claim!` reads the clock and puts this controller's claim on a new reservation.
   3. `settle-provision!` merges the outcome into the reservation, keeping its request and digest,
      and clears the claim.
3. `lifecycle`: before `provision`, `provisioned` re-reads the entry.
   1. A failed read returns the ledger's own anomaly, as every other ledger refusal does.
   2. An expired claim is refused as `claim-lost`.
   3. Either way, nothing is sent to the provider.
4. `validation`: `:claim-ttl-ms` is an optional positive integer.
5. A message and a refusal type (`:conflict`) for `claim-lost`.
6. Test helpers in `ledger-faults`: `later`, `reacquire` and `restarted`.

No new namespace dependencies in `src`.

## Breaking Changes

Entries written before this PR carry no digest. Acquiring an unreleased entry with its own request
returns `conflicting-request`. A provisioned entry is no longer returned as a duplicate. Its lease still works for every
call, release included. Nothing deploys, and the
component is opt-in.

## Design Notes

The guard narrows the window but does not close it. A controller can pass the check, pause, and send
the provision after its claim has expired. Closing that needs the provider to refuse a stale
submission, or orphan discovery to find what it created. Both are later issue #2506 work.

The easy option was to keep comparing request maps. It is rejected because a reconciler compares a
stored reservation against a replayed request. The same moment as a `Date` and as an `Instant`
produces unequal maps.

The claim is cleared when the reservation is provisioned. A reservation cleaned up under a cleanup
intent keeps its claim. Only the provisioning state reads the claim.

## Not in This PR

1. Reconcile and takeover of a reservation whose claim has expired: PR #2524, rebuilt on this. Until
   then, a reservation whose dispatch was refused stays provisioning, and the same request is
   refused as in flight. A provision the ledger missed behaves the same way since #2521.
2. Dispatch checks that another controller took the reservation over: a moved generation, a state
   other than provisioning, or a claim naming another controller. Nothing in this PR can change a
   reservation under its owner, so they arrive with #2524's takeover and the tests that reach them.
3. Orphan discovery, an append-only history, export and restore, and an expired-claim sweep.

## Testing Plan

1. `claim_test`:
   1. A reservation records this controller's claim, with `:claim-ttl-ms` applied, and the request
      digest.
   2. A provisioned entry keeps its digest and drops its claim.
   3. A dispatch whose re-read fails returns the store's anomaly, message and receipt id included,
      and makes no `provision!` call. With the guard refusing by reason alone, the test fails.
   4. A controller whose claim expired before dispatch gets `claim-lost` and makes no `provision!`
      call, and the reservation stays provisioning.
2. `lease_test`: `reserve` takes a claim, and a `Date` and an `Instant` for the same expiry are one
   request, a duplicate. Under `*print-level*` 0 and `*print-length*` 1, a request keeps its digest
   and namespace. A different request still digests differently. The test fails when the digest
   uses the caller's printer settings.
3. The agent-environment suite passes: 96 tests with 352 assertions, including PR #2539's review
   fixes merged in. clj-kondo and stratum-lint are clean.

Fake-provider evidence only. Receipts stay `:profile/unqualified`.

## Deployment Plan

Nothing deploys. The component is opt-in.

## Related Issues/PRs

Issue #2506; parent tracker issue #2439. Stacked on PR #2539. PR #2524 is rebuilt on top of this.

## Checklist

- [x] Claims, digests and the dispatch guard, with tests
- [x] PR #2539 merged; this PR retargeted to `main`
- [ ] CI green, Copilot threads resolved, squash-merged
