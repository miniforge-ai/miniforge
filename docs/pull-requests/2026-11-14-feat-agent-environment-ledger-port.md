<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Agent-environment ledger store port and memory store

## Overview

First PR for issue #2506 (durable environment leases). The environment controller kept its lease
ledger in a private atom: nothing outside one process could read it, and it was lost on restart.
This PR moves the ledger behind a `LedgerStore` port with compare-and-set writes, adds an
in-memory store that keeps the same guarantees for tests and fakes, and makes every lifecycle call
refuse, or return the ledger's anomaly, when the ledger cannot record it.

API surface class 1: in-process EDN.

## Layer

Application: the port and the controller's use of it, with its in-memory reference adapter. The
durable adapter is the next PR.

Base branch: `main`. Depends on: nothing open.

## Motivation

Issue #2506 needs leases that survive a controller restart, are shared by concurrent controllers,
and are never reported as written when the store did not take the write. All three need the
controller to stop owning the ledger's storage. A port comes first so the durable store, the
intent records and the reconciler each land against one fixed contract.

## Changes in Detail

1. `interface.ledger/LedgerStore`: `read-entry`, `write-entry!` (succeeds only while the named
   revision is still the key's; nil means no entry), `entries`. The namespace docstring states the
   three guarantees a store must give: compare-and-set per key, reads return the latest
   acknowledged write (durable stores acknowledge only what survives their restart), revisions
   only grow.
2. `ledger.memory`: an atom-backed store with those guarantees for one process lifetime.
   `interface/memory-ledger` exposes it, documented as tests-and-fakes only.
3. `ledger`: the controller's access. `change!` reads, applies a pure function, writes when the
   result differs, and retries a lost race against the entry that won it, up to `write-attempts`
   (8). A store anomaly with a ledger reason passes through unchanged; any other becomes
   `ledger-unavailable` with the store's anomaly kept as data.
4. `controller`: `create` takes `:ledger`; `claim!`, `settle!` and `settle-provision!` go through
   `ledger/change!` and return its anomaly when the store fails. The pure entry transitions
   (`claimed`, `settled`, `reserved-settled`) are the old map-level ones applied to one entry.
5. `lifecycle`:
   1. Acquire refuses when the claim is not stored. The provider is never called.
   2. Leased calls refuse when the entry cannot be read. A forged tenant is refused first, so a
      caller without the tenant never sees storage details.
   3. Both refusals return the ledger's own anomaly, message and data included, with the refusal
      receipt's id added.
   4. Cancel writes the cancelled state before calling the provider. If that write fails, or the
      entry it leaves no longer admits the lease (another controller released or replaced it between
      the read and the write), the provider is not called.
   5. Release runs the provider's deletes, then records the state. If the record fails, the receipt
      says what applied, with reason `ledger-unavailable`, and the caller gets the ledger's anomaly.
      A retry deletes again and records; the fake reports an absent namespace as already gone.
6. `provisioning`: a provision the ledger does not record hands out no lease. The environment
   exists and the reservation stays provisioning, an unknown outcome the reconciler PR resolves. A
   second acquire of the same request is refused as in flight, so the key is never provisioned
   twice.
7. `validation`: `:ledger` is required and must satisfy `LedgerStore`. There is no default store,
   so a controller cannot end up on a non-durable ledger without asking for one.
8. Messages and refusal types for `ledger-unavailable` (`:unavailable`) and `ledger-conflict`
   (`:conflict`).
9. Conformance fixtures: `world` takes an optional ledger store; `release-all!` lists entries
   through the port.

Import graph (arrows point down; no cycles):

```text
interface ─► lifecycle ─► controller ─► ledger ─► interface.ledger
    │            │                         │
    │            └─► lifecycle.support     └─► refusal ─► messages
    ├─► validation ─► interface.ledger
    └─► ledger.memory ─► interface.ledger, refusal
```

## Breaking Changes

`create-controller` now requires `:ledger`. The open stacked PRs #2455 and #2458 build controllers
without it. When rebased, they need `:ledger (env/memory-ledger)`.

## Design Notes

The easy option was a default memory ledger when `:ledger` is absent. It is rejected because a
hosted controller would then run on a ledger that forgets everything at restart, with nothing
saying so.

Cancel's receipt when its ledger write fails is `:outcome/failed` with no steps, because it goes
through the same path as a failed provider call. Acquire and leased calls that cannot read or
write the ledger are `:outcome/refused`. Neither one reaches the provider.

## Not in This PR

These are the later issue #2506 PRs, in order:

1. A durable file-backed store: create-only and compare-and-set records, fsync before
   acknowledging, and a fresh process reading what an earlier one wrote.
2. Submission identity and digests persisted before provider dispatch, plus a reconciler that
   resolves a provisioning entry left by a lost response or a dead controller before any new
   submission.
3. Release intent written before the deletes, with cleanup requested, applied and verified recorded
   separately. Today a release whose ledger write fails after the deletes ran is retried, not
   prevented.
4. Orphan discovery against a provider inventory.
5. An append-only decision history kept apart from the mutable entries, replayable after restart.
6. Ledger export and restore into a fresh service process.

## Testing Plan

1. `ledger_test`:
   1. The memory store refuses a write naming a stale or absent revision.
   2. `change!` writes only a difference.
   3. `change!` retries a lost race against the winner and reports a key that never settles as a
      conflict.
   4. Any non-ledger store failure becomes `ledger-unavailable`.
2. `ledger_lifecycle_test`, over a counting fake provider and a fault-injecting store:
   1. An unrecorded claim makes zero `provision!` calls.
   2. A provision the ledger misses hands out no lease, and the same request is never provisioned
      twice.
   3. Leased calls make zero `exec!` or `deprovision!` calls while the ledger is unreadable.
   4. An unrecorded cancel makes zero `cancel!` calls, and so does a cancel whose lease a rival
      controller released between its read and its write. This test fails without the fix.
   5. A ledger refusal keeps the store's message and carries the receipt id; a forged tenant is
      refused before the unreadable ledger is consulted.
   6. A release the ledger misses is receipted with its reason and then retried.
   7. Two controllers sharing one ledger share leases, and two racing acquires provision once.
3. The existing agent-environment, agent-worker and opt-in profile tests pass: 67 tests with 257
   assertions before the review fixes, 69 tests with 263 after; agent-worker and opt-in profile, 9
   tests with 42 assertions.
4. clj-kondo and stratum-lint are clean on the component.

All of this is unit and fake evidence. None of it qualifies a real provider, a durable store or a
hosted controller. Receipts stay `:profile/unqualified`.

## Deployment Plan

Nothing deploys. The component is opt-in, and no default project depends on it.

## Related Issues/PRs

Issue #2506; parent tracker issue #2439.

## Checklist

- [x] Ledger port, memory store and controller on the port, with tests
- [x] Zero provider calls when the claim or the cancel is not recorded
- [ ] CI green, Copilot threads resolved, squash-merged
