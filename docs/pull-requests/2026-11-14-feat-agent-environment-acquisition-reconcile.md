<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Reconcile expired acquisition claims before resubmission

## Overview

Fifth PR for issue #2506. When a controller dies or its provider response is lost, its reservation
stays provisioning. Once the reservation's claim has expired, a later acquire of the same request
reconciles it before submitting anything new. It asks the provider what exists for the key, then:

1. adopts a ready environment of the reserved generation;
2. otherwise deletes what the key left behind, under a cleanup intent;
3. with nothing left, releases the reservation and provisions the next generation.

A cleanup left uncertain is reconciled the same way, so it no longer blocks its key. A controller
that records its provision after another controller took the reservation over hands out no lease.
It gets the state's refusal, or deletes what it created and gets `claim-lost`.

API surface class 1: in-process EDN.

## Layer

Domain (the lease rules) and Application (the controller and the lifecycle).

Base branch: `feat/agent-environment-acquisition-claims`. Depends on PR #2540, which is stacked on
PR #2539. Both are open. Retarget to `main` once they merge.

## Motivation

Without this, a provision whose ledger record failed, or whose controller crashed between claiming
and recording, leaves its key in `:state/provisioning`, and every later acquire is refused as in
flight. A cleanup that could not be confirmed and had no UID left the key `cleanup-uncertain` for
good. Issue #2506's first acceptance test asks that replaying an acquisition after a restart, at any
persist, submit or acknowledge boundary, yields one authoritative environment, or that an explicit
unknown outcome is reconciled before another submission.

This PR was rebuilt after review. The claims and digests moved to PR #2540 to keep each PR under the
600-line budget. Copilot's six findings on the first version are fixed here or in PR #2539, as
listed under Review Findings.

## Changes in Detail

1. `lease`:
   1. `acquire-decision` takes `now`. A provisioning entry whose claim has expired, and a cleanup left
      uncertain, yield `:reconcile` instead of a refusal.
   2. `reconciled`: the entry a reconcile leaves, provisioned with the adopted environment or
      released, with the claim cleared.
   3. `claim-held?`: the entry is still the reservation at the given generation, under the given
      controller's unexpired claim.
2. `controller`: `claim!` decides at the clock's `now`, and a `:reconcile` decision carries the stale
   entry. `settle-reconciled!` applies a reconcile only while the stale entry is still the key's exact
   entry, so two controllers reconciling at once settle it once.
3. `lifecycle.reconcile/reconcile!`, new, observes the provider at the key's namespace:
   1. A ready environment carrying the reservation's generation is adopted. A partial one is never
      adopted.
   2. Otherwise the entry's own environment, or an object the provider shows under this or an earlier
      generation, is deleted under a cleanup intent on the exact stale entry.
   3. With nothing of the key's left, the reservation is released.
   4. A provider that cannot answer leaves the entry as it is, and the acquire gets the provider's
      anomaly. "Cannot see it" is never read as "nothing exists".
   5. Each reconcile is receipted under the new operation `:environment/reconcile`, with a
      `:step/adopted` or `:step/abandoned` step.
   6. It then claims the request again and returns the fresh decision.
4. `lifecycle`:
   1. `acquisition` acts on a claim decision. After a reconcile it acts on the fresh decision once: a
      second `:reconcile` is refused with the entry's state reason.
   2. The dispatch guard from PR #2540 now uses `claim-held?`. A rival that reconciled and
      re-reserved the key between this controller's claim and its provider call is seen, and nothing
      is sent.
5. `lifecycle.provisioning/adopted`, when the owner records its provision:
   1. If the entry no longer holds the owner's lease, another controller reconciled the reservation.
      The owner deletes what it created under a cleanup intent on the reservation the reconcile
      released, and returns `claim-lost` with the receipt ids and UID. If a newer generation already
      holds the key, the intent is not taken and nothing is deleted. The UID in the refusal is left
      for orphan discovery.
   2. If the entry holds the owner's lease but another controller moved it on (adopted, then
      cancelled or released), the owner gets that state's refusal and deletes nothing.
6. `lifecycle.cleanup/cleaned-under!` replaces `reservation-cleaned!`. The caller passes the
   ownership rule the intent is written under (`for-reservation`, `for-abandoned` or `for-stale`) and
   the receipt's reason.
7. `support/applied-step`; the `:environment/reconcile` operation in the receipt schema.
8. Test helpers in `ledger-faults`: a one-shot `:before-read` hook beside `:before-write`, and
   `abandon!`, `at-record` and `late-controller`, shared by the reconcile and takeover tests.

Import graph (arrows point down; no cycles):

```text
lifecycle ─► lifecycle.reconcile ─► lifecycle.cleanup ─► controller, lease
    └─► lifecycle.provisioning ─► lifecycle.cleanup
```

## Review Findings

Copilot's six findings on the first version of this PR, and where each is fixed:

1. A cleanup left uncertain with no environment could never be reconciled. Fixed: cleanup-uncertain
   yields `:reconcile`, and the reconcile deletes what the provider shows for the key.
2. A controller could pause after its claim and submit after a takeover. Narrowed: the dispatch
   guard re-reads the reservation just before the provider call. A pause after that check is closed
   only by orphan discovery or a provider that refuses a stale submission.
3. An owner could delete an environment another controller had adopted. Fixed: the owner's cleanup
   intent applies only to the reservation a reconcile released with no environment.
4. An environment adopted and then released or cancelled could be returned to the late owner. Fixed:
   the owner checks the entry's state, not only its lease.
5. A failed claim-lost cleanup left nothing in the ledger. Fixed: the cleanup goes through a cleanup
   intent (PR #2539), so an unconfirmed delete stays `cleanup-uncertain` and is retried.
6. A partial environment could be adopted. Fixed: only a ready environment is adopted, and a partial
   one is deleted.

## Design Notes

The easy option was to reconcile any provisioning entry on sight. It is rejected because the owner
may still be provisioning, and a reconciler that adopted a half-built environment, or released a
reservation whose objects were about to appear, would race it. The claim TTL gives the owner a
bounded window. Past it, the claim-lost path keeps a late owner from leaking what it built.

Claims compare expiry times written by one controller against another controller's clock. Clock
skew between controllers shortens or lengthens the window by the skew, so the TTL must exceed the
slowest provision plus its cleanup, plus that skew.

A request that conflicts with a stuck reservation's digest is refused as conflicting and does not
trigger a reconcile.

## Not in This PR

1. Orphan discovery against a provider inventory. A late owner whose cleanup intent was not taken,
   and a partial provision whose UID is unknown, are found by it.
2. A sweep that reconciles expired claims without waiting for a replay.
3. An append-only decision history, and ledger export and restore.

## Testing Plan

1. `lease_test`:
   1. An expired claim yields `:reconcile`; a provisioned entry past the claim's end is still a
      duplicate; a cleanup left uncertain, with or without a UID, yields `:reconcile`.
   2. `claim-held?` is false for another generation, another controller, the claim's end, or an
      entry with no claim.
   3. `reconciled` adopts or releases.
2. `reconcile_test`, with a controller whose clock is past every claim:
   1. A lost provision response, replayed: one `provision!` call in all, the environment adopted and
      ready, one `:step/adopted` reconcile receipt.
   2. A claim recorded just before a crash: the reservation is released and generation 2
      provisioned, with one `provision!` call in all.
   3. An unexpired claim is left alone: zero `observe` and zero `provision!` calls.
   4. A provider that cannot answer: refused with its anomaly, the reservation still provisioning.
   5. An owner whose reservation was abandoned before it recorded gets `claim-lost`, and nothing it
      made is left running.
3. `takeover_test`:
   1. A late owner gets the adopted environment's refusal and deletes nothing.
   2. A partial environment is deleted before the next generation, not adopted.
   3. A cleanup left without a UID is reconciled and the key reused.
   4. A late owner's unconfirmed delete stays `cleanup-uncertain`, and the next acquire deletes it.
   5. A rival that reconciled and provisioned the next generation between the owner's claim and its
      dispatch: the owner gets `claim-lost`, and the rival's is the only `provision!` call.
   6. A provision that ends unconfirmed after a rival adopted its environment deletes nothing: the
      owner's cleanup intent is not taken, and the entry stays provisioned.
4. Each of the six review findings has a test that fails with its fix removed, checked by mutation on
   this version. The test for finding 3 is new in this version.
5. The agent-environment suite passes: 104 tests with 380 assertions. clj-kondo and stratum-lint are
   clean.

All of this is fake-provider evidence. A real provider's `observe` must be authoritative for the
generation and readiness it reports, and that is not shown here. Receipts stay
`:profile/unqualified`.

## Deployment Plan

Nothing deploys. The component is opt-in.

## Related Issues/PRs

Issue #2506; parent tracker issue #2439. Stacked on PR #2540, which is stacked on PR #2539. It
follows PRs #2521 and #2522, both merged.

## Checklist

- [x] Reconcile, takeover and the late-owner path, with tests
- [ ] PRs #2539 and #2540 merged; retarget to `main`
- [ ] CI green, Copilot threads resolved, squash-merged
