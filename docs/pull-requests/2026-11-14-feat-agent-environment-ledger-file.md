<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Durable file-backed ledger store for agent environments

## Overview

Second PR for issue #2506. It adds `file-ledger`, a `LedgerStore` that keeps one record file per
tenant/run/task key under a directory. A controller started later, in the same process or another,
reads every write an earlier one acknowledged, so leases survive a controller restart.

API surface class 1: in-process EDN.

## Layer

Infrastructure: a durable adapter for the port from the first PR.

Base branch: `feat/agent-environment-ledger-port`. Depends on PR #2521, which is open. Retarget to
`main` once it merges.

## Motivation

The first PR put the ledger behind a port but shipped only a memory store, which forgets every lease
when its process exits. Issue #2506 needs leases, generations and reservations that survive a
restart, and it asks for an existing durable-store abstraction where one fits. This store uses the
`file-durability` component's forced publication, the same one the event-stream file sink uses.

## Changes in Detail

1. `ledger.disk`, the file mechanics. Each operation returns a result or a `ledger-unavailable`
   anomaly that names the operation and path.
   1. Record and lock files are named by a content hash of the key.
   2. Records are EDN. Instants are written under `#mf/instant` because `#inst` reads back as a
      `Date`, which does not equal the stored `Instant`; after a restart every stored request
      would look changed and be refused as conflicting.
   3. A read decodes the whole record and checks it against `StoredRecord`. A file that does not
      match is corrupt, and the ledger reports itself unavailable instead of guessing.
   4. A write goes to a temporary file in the same directory. `file-durability/publish-bytes!`
      forces the bytes, the atomic rename, and the directory entries before the write is
      acknowledged.
   5. Every write to a key holds that key's file lock, which excludes other processes. It first
      takes one of 64 striped monitors, because a JVM that already holds a file lock throws when
      it asks again.
2. `ledger.file/FileLedger`: `write-entry!` reads the stored revision under the key's lock,
   refuses a stale one as `ledger-conflict`, and publishes revision + 1. `read-entry` and `entries`
   read without the lock; records are only renamed into place, so a reader sees an old record or a
   new one, never part of either.
3. `interface/file-ledger`, validated by `LedgerDir` (a non-blank path or a `File`).
4. `interface.ledger/absent`: the absent-read value, now shared by both stores.
5. Tests: the shared `controller-over` helper moves into `ledger-faults`.

Import graph (arrows point down; no cycles):

```text
interface ─► ledger.file ─► ledger.disk ─► interface.ledger, refusal, file-durability, content-hash
                └─► interface.ledger
```

## Design Notes

The easy option was one EDN file holding the whole ledger, rewritten on every change. It is
rejected because every write would contend for the same lock and rewrite every other key's lease.
One file per key keeps writes independent and leaves the compare-and-set check local to one record.

A `java.sql.Timestamp` in a request is written as `#inst` and reads back as a `Date`. After a
restart, that request looks changed and is refused as conflicting, a fail-closed outcome. Requests
built by the conformance fixtures and the pilot use `Instant`.

Lock files stay on disk after their key is released: one empty file per key.

## Testing Plan

1. `file_ledger_test`:
   1. A fresh store reads what an earlier one wrote: entry, revision and listing, with a
      nanosecond `Instant` and a `Date` both round-tripping.
   2. A write naming a stale revision is a conflict.
   3. Two stores race on one directory: a barrier makes both read the same revision first. Both
      changes land, the final revision is 2, and the loser retried against the winner's entry.
   4. A corrupt record makes reads, writes and listing unavailable.
   5. A directory that cannot be created is unavailable.
   6. A restarted controller over a fresh store on the same directory sees the earlier lease as
      ready, returns the same environment for the same request, and releases it. The original
      controller then sees the lease as released.
2. Each file test runs in under 100 ms, measured locally.
3. The agent-environment suite passes: 73 tests with 273 assertions. Agent-worker and opt-in
   profile pass: 9 tests with 42 assertions. clj-kondo and stratum-lint are clean.

This is local-filesystem evidence on one host. It does not qualify a shared volume, a hosted
deployment, or durability across a host crash. Receipts stay `:profile/unqualified`.

## Deployment Plan

Nothing deploys. The component is opt-in. Choosing a directory on durable storage is up to the
service that composes it.

## Related Issues/PRs

Issue #2506; parent tracker issue #2439. Stacked on PR #2521.

## Checklist

- [x] File store with restart, conflict, race, corruption and lifecycle tests
- [ ] PR #2521 merged; retarget this PR to `main`
- [ ] CI green, Copilot threads resolved, squash-merged
