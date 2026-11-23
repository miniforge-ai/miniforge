<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Cleanup intent before every environment delete

## Overview

Third PR for issue #2506. Every delete of an agent environment now goes through the ledger. A
compare-and-set first records the intent: the UID and generation to delete, and when it was
requested. Only a caller whose intent the ledger took sends the delete. The outcome is then recorded
against that intent in stages: applied when the provider confirms the deletes, verified when the
target no longer shows the UID. Only a verified cleanup releases the entry.

API surface class 1: in-process EDN.

## Layer

Application (the lifecycle's release and partial-provision paths), with one new ledger state.

Base branch: `main`. Depends on nothing open. PR #2524 (expiring claims and reconcile) is reworked on
top of this.

## Motivation

On `main`, a release called the provider's delete and only then wrote the ledger. If that write
failed, the deletes had run while the ledger still showed the environment provisioned, which #2521
documented as a known gap. Cleanup after a partial provision did the same. Neither recorded whether
the UID was actually gone. Copilot's review of #2524 showed why this matters for takeover: a
controller could delete an environment another controller had adopted, and a failed cleanup left a
resource nothing in the ledger pointed at. Recording the intent first gives each delete one owner
and a durable record that survives a crash between the delete and its outcome.

## Changes in Detail

1. `lifecycle.cleanup`, new:
   1. `intent`: `{:cleanup/attempt :cleanup/uid :cleanup/generation :cleanup/requested-at
      :cleanup/applied? :cleanup/verified?}`. Each attempt gets a random id, so two attempts at one
      clock instant hold different intents, and each owns only the intent its own write recorded.
   2. `released!` writes the intent under the caller's lease. The write moves the entry to the new
      `:state/releasing` only while that entry still admits a release under the lease. A caller
      whose intent the ledger did not take is refused, and nothing is deleted.
   3. It then deletes by UID and observes the target. The delete is verified when the UID is no
      longer there, and an observation that fails verifies nothing. The outcome is written only to
      an entry that still holds this intent: `:state/released` only when the deletes applied and are
      verified, otherwise `:state/cleanup-uncertain` to retry. A UID that is gone does not release
      an entry whose deletes the provider reported failed, since its delete may cover more than the
      namespace. The receipt adds a `:step/verified` step, so a release is
      `:outcome/applied` only when its deletes are verified. If another attempt replaced the intent
      before this one recorded its outcome, and the entry is not released, the call returns the
      entry's refusal (`environment-releasing` or `cleanup-uncertain`), so it is retried rather
      than reported done.
   4. `reservation-cleaned!` does the same for a partial provision. The intent goes on the
      reservation and applies only while that generation is still provisioning. It records the
      environment, so its lease can retry the release.
2. `lifecycle`: `release!` uses `cleanup/released!`. The old delete-then-settle path is removed.
3. `lifecycle.provisioning`: `provision-failed` cleans up through `reservation-cleaned!`. When nothing
   is to be deleted, `uncleaned!` records released (every step failed) or cleanup-uncertain. When the
   ledger does not record the outcome, its anomaly still carries the receipt ids, and the
   environment's lease when the cleanup intent was stored, so the caller can retry the release. When
   the intent was not stored, no lease is offered: the entry holds no environment, so the lease
   could not be used. That reservation stays provisioning until PR #2524's reconcile deletes the
   partial environment.
4. `lease`: release is admitted in `:state/releasing`, so a release interrupted between its delete
   and its outcome can be retried. Every other call, and acquire, is refused as
   `environment-releasing`.
5. `controller/change-entry!`: a compare-and-set by key, for the reservation path.
6. A message and a refusal type for `environment-releasing`.
7. Test helpers: `ledger-faults/controller-over` takes an optional map of controller options.

Import graph (arrows point down; no cycles):

```text
lifecycle ─► lifecycle.cleanup ─► controller, lease, lifecycle.support, refusal, receipt
    └─► lifecycle.provisioning ─► lifecycle.cleanup
```

## State Names

Issue #2506 names acquiring, ready, releasing, unknown and released. `:state/releasing` is new.
`:state/cleanup-uncertain` is the unknown cleanup, and `:entry/cleanup` records requested, applied
and verified separately.

## Design Notes

The easy option was to keep delete-then-record and retry the record. It is rejected because between
the delete and the record nothing durable says a delete was sent. Another controller, or the same
one after a restart, cannot tell an environment about to be deleted from one still in use.

Verification observes the target once after the deletes. A Kubernetes namespace keeps its UID while
terminating, so a real provider's release will often end `cleanup-uncertain` and be retried until
the namespace is gone. That is the intended reading of an unconfirmed delete.

## Not in This PR

1. PR #2524's rework: it routes the claim-lost cleanup through this intent. It checks the state, not
   only the lease, when an owner records late, and it does not adopt an environment that is not
   ready.
2. A partial provision whose UID is unknown is still never deleted by name. It stays
   cleanup-uncertain with no lease, which needs the reconcile path or orphan discovery to clear.

## Testing Plan

1. `cleanup_test`:
   1. The intent is recorded before any delete: no `deprovision!` call when the intent write lands.
      The released entry records the UID, applied and verified.
   2. A release whose outcome write fails stays `:state/releasing`. Its deletes ran, other calls are
      refused, and a retry deletes again and is verified.
   3. An observation still showing the UID after the delete leaves `:state/cleanup-uncertain`
      (applied, not verified) and an uncertain outcome. The next attempt, with the UID gone, releases.
   4. A partial provision is cleaned up under an intent and released when verified. When the ledger
      cannot take the intent, nothing is deleted and the reservation stays provisioning.
   5. Acquire is refused while a release is pending.
   6. A retry at the same clock instant writes and settles its own intent, not the first attempt's.
   7. A delete the provider reports failed, with the UID gone, leaves `:state/cleanup-uncertain`; a
      retry whose delete applies releases.
   8. A partial provision whose cleanup outcome the ledger misses returns the lease in the ledger's
      anomaly, and releasing that lease finishes the cleanup. One whose intent the ledger misses
      offers no lease.
   9. A release whose intent an overlapping attempt replaced, and which that attempt left releasing,
      returns `environment-releasing`; a retry releases.
2. `ledger_lifecycle_test`: a release the ledger cannot record now deletes nothing (no
   `deprovision!` call). It replaces #2521's test that pinned the old delete-then-record gap.
3. The first five `cleanup_test` tests fail against `main`'s lifecycle (16 assertions) and pass here.
   Tests 6 to 9, added for review, each fail with their fix removed.
4. The agent-environment suite passes: 90 tests with 339 assertions. clj-kondo and stratum-lint are
   clean.

Fake-provider evidence only. A real provider's observe must be authoritative for the UID it reports.
Receipts stay `:profile/unqualified`.

## Deployment Plan

Nothing deploys. The component is opt-in.

## Related Issues/PRs

Issue #2506; parent tracker issue #2439. Follows #2521 and #2522, both merged. PR #2524 is reworked on
top of this.

## Checklist

- [x] Cleanup intent before every delete, outcome recorded in stages, with tests
- [ ] CI green, Copilot threads resolved, squash-merged
