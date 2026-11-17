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
   1. Record and lock files are named by a content hash of the key's canonical EDN, which keeps
      types apart: a UUID key and a string key that print alike name different files. A read
      also checks the stored key equals the key asked for. The hash and the record bytes
      are printed through `ledger.printing`, which binds every printer setting (`*print-length*`,
      `*print-readably*`, `*print-dup*` and the rest), so a caller's bindings cannot rename a file
      or write bytes `clojure.edn` reads differently. Bytes are encoded and decoded with reporting
      UTF-8 coders: a string with no UTF-8 form, such as an unpaired surrogate, is refused before
      anything is written, and malformed UTF-8 on disk is `ledger-unavailable` rather than
      replacement characters.
   2. Records are EDN. Instants are written under `#mf/instant` because `#inst` reads back as a
      `Date`, which does not equal the stored `Instant`; after a restart every stored request
      would look changed and be refused as conflicting.
   3. A read decodes exactly one form (the end-of-input marker is a fresh object, so no readable
      value can pass for it) and checks it against `StoredRecord` and against its own file
      name. Trailing content, a malformed record, or another key's record is corrupt, and the
      ledger reports itself unavailable instead of guessing. Only a missing file reads as absent,
      and only a missing directory lists as empty. Any other I/O failure is `ledger-unavailable`.
   4. A write goes to a temporary file in the same directory. `file-durability/publish-bytes!`
      forces the bytes, the atomic rename, and the directory entries before the write is
      acknowledged.
   5. Every write to a key holds that key's file lock, which excludes other processes. It first
      takes one of 64 striped monitors, chosen by the lock file's key-hash name so path aliases of
      one directory share it, because a JVM that already holds a file lock throws when it asks
      again.
2. `ledger.file/FileLedger`: `write-entry!` reads the stored revision under the key's lock,
   refuses a stale one as `ledger-conflict`, encodes the record and decodes it again, refusing it
   unless it reads back equal, and publishes revision + 1. A `Timestamp` (which EDN reads back as
   a `Date`), a value with no EDN form, or a non-map entry is refused with nothing written, never
   acknowledged and then unreadable. `read-entry` and `entries` read without the lock; records
   are only renamed into place, so a reader sees an old record or a new one, never part of either.
3. `interface/file-ledger`, validated by `LedgerDir` (a non-blank path or a `File`).
4. `interface.ledger/absent`: the absent-read value, now shared by both stores.
5. Tests: the shared `controller-over` helper moves into `ledger-faults`.
6. A cross-process integration test, `projects/agent-environments/test`: two writer JVMs both read
   the absent revision, then write. Only the file lock separates them, since the JVM monitors are
   per process. `bb test:integration` now runs this project's `test` root, through an
   `:integration` alias so Polylith's `:test` runs do not start JVMs.

Import graph (arrows point down; no cycles):

```text
interface ─► ledger.file ─► ledger.disk ─► interface.ledger, refusal, file-durability, content-hash
                └─► interface.ledger
```

## Design Notes

The easy option was one EDN file holding the whole ledger, rewritten on every change. It is
rejected because every write would contend for the same lock and rewrite every other key's lease.
One file per key keeps writes independent and leaves the compare-and-set check local to one record.

A `java.sql.Timestamp` in an entry would be written as `#inst` and read back as a `Date`, so the
round-trip check refuses that write with `ledger-unavailable` before anything is published; the
lease is never acknowledged. Requests built by the conformance fixtures and the pilot use `Instant`.

Lock files stay on disk after their key is released: one empty file per key.

## Testing Plan

1. `file_ledger_test`:
   1. A fresh store reads what an earlier one wrote: entry, revision and listing, with a
      nanosecond `Instant` and a `Date` both round-tripping.
   2. A write naming a stale revision is a conflict.
   3. Two stores race on one directory, the second through a `/.` alias. Each takes its read
      before a barrier, so both hold the same revision. Both changes land, the final revision is 2,
      and the loser retried against the winner's entry.
   4. A corrupt record makes reads, writes and listing unavailable, and so do trailing content and
      another key's record copied over this key's file.
   5. A store under a regular file is unavailable for writes, reads and listing, never empty.
   6. Keys and a string value written under `*print-length*` 1 and `*print-readably*` false get
      separate files and read back equal; a trailing `:...ledger.disk/eof` keyword is still trailing
      content.
   7. A restarted controller over a fresh store on the same directory sees the earlier lease as
      ready, returns the same environment for the same request, and releases it. The original
      controller then sees the lease as released.
2. `file_ledger_process_test` (integration, about 6 s): exactly one of the two writer processes
   lands revision 1 and the other gets `ledger-conflict`. Both exit 0; a writer that has not exited
   by the step timeout is killed and fails the test. The loser's retry against revision 1
   lands, and a fresh process started after the writers exit reads it. With the file lock removed,
   the test failed 3 of 3 runs.
3. Each component file test runs in under 100 ms, measured locally.
4. `file_ledger_test` also pins lossless UTF-8: an unpaired surrogate is refused with nothing
   written, and a malformed byte in a stored string is unavailable.
5. A write that would not read back (a `Timestamp`, a `File`, a non-map entry) is refused with
   nothing written, and a UUID key and a string key that print alike keep separate records.
6. The agent-environment suite passes: 81 tests with 301 assertions. Agent-worker and opt-in
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
