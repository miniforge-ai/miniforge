<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Reconcile a pending cleanup no lease holder can retry

## Overview

Sixth PR for issue #2506. A cleanup intent written without a lease now carries the cleaner's claim.
That covers three cleanups: a partial provision's own, a late owner's, and a reconcile's. If the
cleaner stops between its intent and its outcome, the entry stays releasing. Once that claim expires,
an acquire of the same request reconciles the entry: it deletes the environment under a new intent
and then acts on a fresh decision. A release under a lease carries no claim, and its lease holder
retries it as before.

API surface class 1: in-process EDN.

## Layer

Domain (the lease rule) and Application (the cleanup).

Base branch: `main`. PRs #2539, #2540 and #2524, which this was stacked on, are merged.

## Motivation

Copilot's review of PR #2524 found that a reconcile interrupted after its cleanup intent, or whose
outcome write failed, left the entry releasing. No caller held a lease for it, so every later acquire
was refused as `environment-releasing` and the key stayed blocked after the ledger recovered. The fix
is split out of PR #2524 to keep that PR under the 600-line budget.

## Changes in Detail

1. `lifecycle.cleanup`:
   1. `releasing` puts the cleaner's claim on the entry.
   2. `cleaned-under!` builds the claim with `controller/claim-for` at the intent's time.
   3. The ownership rules `for-reservation` and `for-stale` take the claim.
   4. `requested-under-lease` clears the claim. A partial provision's cleanup whose outcome went
      unrecorded leaves its owner's claim on the entry. When the caller retries with the lease it
      was handed, the release becomes the lease holder's, and no acquire reconciles it, even after
      the old claim expires.
2. `controller/claim-for` is public, for the cleanup.
3. `lease/acquire-decision`: a releasing entry whose claim has expired yields `:reconcile`. The
   reconcile does not adopt it, since it is not provisioning. It deletes the entry's environment
   under a new intent with a new attempt id, so a late outcome from the interrupted cleaner no longer
   matches. A releasing entry with no claim, a release under a lease, is still refused.
4. `ledger-faults/much-later`: a clock past every claim made on a `later` clock.

## Design Notes

The easy option was a separate cleanup timeout. It is rejected because the claim already records who
is acting and until when, and `acquire-decision` already reads it. While the claim lasts, another
acquire is refused as `environment-releasing`, so a cleaner that is still working is not raced. The
clock-skew caveat from PR #2524 applies to this claim too.

## Testing Plan

1. `lease_test`: a releasing entry is reconciled past its claim's end, refused as
   `environment-releasing` before it, and refused when it carries no claim.
2. `takeover_test`: a reconcile's cleanup intent lands, and its outcome write fails. While the
   cleaner's claim lasts, the next acquire is refused as `environment-releasing`. After it expires,
   the next acquire finishes the cleanup and provisions generation 2, with one namespace live.
3. `cleanup_test`: a lease holder's retry of that cleanup, whose own outcome write also fails,
   leaves the entry with no claim. An acquire past the old claim is still refused as
   `environment-releasing`.
4. Each of these tests fails with its fix removed, checked by mutation: the takeover test without
   the releasing arm in `acquire-decision` or without the claim on the entry, and the cleanup test
   without the claim cleared under a lease.
5. The agent-environment suite passes: 112 tests with 413 assertions. clj-kondo and stratum-lint are
   clean.

Fake-provider evidence only. Receipts stay `:profile/unqualified`.

## Deployment Plan

Nothing deploys. The component is opt-in.

## Related Issues/PRs

Issue #2506; parent tracker issue #2439. Stacked on PR #2524.

## Checklist

- [x] Pending lease-less cleanups reconcilable past the cleaner's claim, with tests
- [x] PRs #2539, #2540 and #2524 merged; this PR retargeted to `main`
- [ ] CI green, Copilot threads resolved, squash-merged
