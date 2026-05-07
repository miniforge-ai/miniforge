# Event-stream replay, retention, and quiesce (BD-2)

Status: **draft v3**, opened 2026-05-07, revised twice 2026-05-07 after
design review. Author: Christopher Lester (`miniforge-control` BD-2).

This RFC scopes three coupled producer-side problems that surface as
consumer pain in `miniforge-control`:

- **BD-2a** — `--headless` exit races the event-sink drain.
- **BD-2b** — `~/.miniforge/events/` grows unbounded between sessions; the
  only retention is a 7-day TTL that runs *async on sink creation* and
  never under load.
- **BD-2c** — Cold-start replay scales linearly with on-disk events. ~60k
  events on the consumer (Rust TUI) already produces multi-minute startup.

The control-plane consumer can mitigate cold start with a history window
(`UX-K`), but cannot fix the producer-side race or the unbounded log. This
RFC proposes the upstream changes that make the consumer's job tractable
and tracks them as a sequenced burndown across two repos.

## v3 changes vs v2

- Cross-cutting filename scheme replaced. v2's
  `{seq:020d}__{ts}__{uuid}.json` was workflow-sequence-leading, which
  fixes lexical sort but couples filenames to per-workflow logical order
  and leaves no globally-sortable identifier. v3 introduces a separate
  Snowflake-style **`:event/id`** (41-bit ms timestamp / 10-bit worker /
  12-bit per-ms sequence) carried on every event envelope. Filenames
  become `{event-id-hex16}__{workflow-seq-dec12}.transit.json`.
- Snapshot watermarks now carry **both** `last-event-id` and
  `workflow-sequence-number`. The event id lets readers skip old files
  without parsing payloads; the workflow sequence verifies
  workflow-contiguous replay.
- Clock-rollback handling and worker-id leasing added explicitly. Both
  are real concerns for a Snowflake-style ID generated locally without a
  central authority.
- Range-query complexity claim re-tightened. Snowflake IDs improve
  "skip old events without parsing payloads" — they do not by themselves
  produce `O(events-in-window)` on a plain filesystem. The bucketed-dir
  / index-file follow-on stays out of scope.

## v2 changes vs v1

This revision incorporates external design review. Material changes:

- BD-2a now distinguishes **`quiesce!`** (publisher fencing) from
  **`drain!`** (sink durability barrier). The original single-primitive
  proposal didn't actually close the race window for late background
  publishers.
- BD-2a `drain!` returns a **structured result**; headless exits non-zero
  on timeout or sink failure unless `--best-effort-shutdown` is set.
- BD-2b adopts an explicit **workflow manifest + lease** liveness model.
  TTL-by-mtime alone misclassifies quiet active workflows.
- BD-2b separates **raw event-tail retention** from **summary
  retention**. Deleting an archive after 30 days no longer destroys the
  workflow summary.
- BD-2b defines an **atomic archive operation** (temp → manifest stage →
  rename → marker) that is crash-safe and idempotent.
- BD-2c adds **periodic active-workflow checkpoints**. Snapshot-on-archive
  alone does not bound replay for long-running active workflows.
- BD-2c clarifies that **crashed workflows** synthesize a snapshot via
  replay during slow-path archival, or omit the snapshot — it is not
  silently empty.
- Cross-cutting: filename grammar changed to **`{seq:020d}__{ts}__{uuid}.json`**
  (sequence-leading, double-underscore delimiter), and the complexity
  claim is corrected — lexical sort now matches sequence order, but
  directory enumeration remains `O(files-in-dir)` without an index.
- Added an explicit **on-disk contract** section. The Clojure reader API
  is incidental; the actual cross-repo contract is the filesystem layout.

## Context

Today's event-stream contract:

- **Storage** — one Transit-JSON file per event under
  `~/.miniforge/events/{workflow-id}/{ts}-{uuid}.json`
  (`components/event-stream/src/.../sinks.clj:60-71`).
- **Envelope** — every event carries `:event/sequence-number` per
  workflow (`event_stream/core.clj:56-67`), but readers do not surface it.
- **Reader** — full directory read, sort by filename, no range queries
  (`event_stream/reader.clj:81-103`).
- **Producer publish** — `publish!` notifies subscribers async and
  returns immediately; no drain primitive
  (`event_stream/core.clj:104-142`).
- **Retention** — `cleanup-stale-events!` deletes files older than 7 d
  (configurable), invoked once on sink creation in a fire-and-forget
  future (`sinks.clj:115-159`). Concurrent workflow-heavy loads can
  retain far more than the TTL before cleanup fires.
- **Snapshot story** — the upstream `supervisory-state` accumulator is a
  pure reducer, but the component does **not** replay stored events on
  start; comment at `core.clj:100-101` explicitly says
  "YAGNI — no production caller attached to a pre-populated stream".
  The `:supervisory/workflow-upserted` family of events is *output* from
  the accumulator, not snapshot input. This asymmetry is what BD-2c has
  to resolve.

The actual replay pain lives in the consumer: `miniforge-control`'s Rust
TUI tails the event directory and rebuilds its own entity table on every
start. So the snapshot/bounded-replay design has to span both repos —
and the cross-repo contract is the **on-disk format**, not any
language-level API.

## Goals

1. `--headless` exit either guarantees all events the pipeline produced
   are durable on disk before the process returns, or exits non-zero
   with a structured failure naming the unflushed sinks. No silent
   loss.
2. The on-disk event log has bounded, predictable size with retention
   policy controlled by an explicit knob, not a side effect of sink
   construction. Active workflows are never evicted.
3. Cold-start replay cost on the consumer is bounded by a configurable
   window for **completed** workflows and by a checkpoint-relative tail
   for **active** workflows. A workflow that completed a year ago does
   not pay startup cost today.
4. Old workflow context is still investigable. Workflow summaries are a
   separate retention tier from raw event tails — raw events can be
   reaped while summaries persist.

## Non-goals

- Changing the event envelope shape (`:event/version "1.0.0"`).
- Cross-host replay or distributed event log. Stays single-machine,
  single-user.
- Retroactive snapshotting of the existing 60k-event log on
  developers' disks — the consumer's `UX-K` window is the migration
  story for already-resident history.

---

## BD-2a — Producer quiesce + sink drain on shutdown

**Race window.** `bases/cli/src/.../workflow_runner.clj:781-810`
returns from `run-workflow!` as soon as `execute-workflow-pipeline`
completes. `event_stream/core.clj:104-142` `publish!` is fire-and-forget;
late events from background producer threads (agent heartbeats, phase
cleanup) can land after `run-workflow!` has returned and the process is
already tearing down. There is no barrier between "pipeline done" and
"all events durable", and there is no fence that prevents background
publishers from emitting *after* a barrier is in place.

A simple sink drain primitive does not close the race: by the time it
captures the in-flight set, a heartbeat thread can already be queueing
the next event. We need two primitives.

### Primitives

```clojure
(event-stream/quiesce! stream {:workflow-id wid :timeout-ms 5000})
;; Reject (or wait out) future publishers for the workflow.
;; After return, publish! for that workflow returns
;; {:rejected? true :reason :workflow-quiesced}.
;; Returns {:ok? bool :pending-publishers N}.

(event-stream/drain! stream {:timeout-ms 5000})
;; Wait until events accepted before this call have reached all
;; configured sinks, including parent-directory fsync where supported.
;; Returns a structured status (see below).
```

`quiesce!` is workflow-scoped. The producer-side contract is:

- `:workflow/completed` and `:workflow/failed` are by definition the
  *final* events for that workflow. `quiesce!` enforces this — any
  later `publish!` for that workflow id is rejected with a logged
  warning and returns a non-throwing rejection result. Heartbeat /
  cleanup threads must check the rejection result and stop.
- Workflow lifecycle owns quiesce: phase cleanup, heartbeat threads,
  and agent background publishers all register via the workflow
  runtime, which calls `quiesce!` *before* emitting the terminal event,
  not after.

### Drain return shape

```clojure
;; Success
{:ok? true
 :drained-count N
 :sinks {:file {:ok? true :written N :fsynced N}
         :stdout {:ok? true :written N}}}

;; Timeout
{:ok? false
 :reason :timeout
 :pending-count M
 :undrained-sinks [:file]
 :drained-count K}

;; Sink error
{:ok? false
 :reason :sink-error
 :failed-sinks {:file {:error "..." :failed-events 3}}
 :drained-count K}
```

### Headless shutdown sequence

```text
1. Stop workflow producers (pipeline complete).
2. Stop heartbeats / agent background publishers for the workflow.
3. quiesce! the workflow → terminal-event publish is the last accepted.
4. Emit :workflow/completed | :workflow/failed | :workflow/cancelled.
5. drain! the stream.
6. Inspect drain result.
7. Headless exits non-zero on :timeout or :sink-error unless
   MINIFORGE_BEST_EFFORT_SHUTDOWN=1 is set.
8. Cleanup that does not publish events runs after drain.
```

Cleanup that *does* publish events (e.g. capsule teardown emitting an
`:agent/teardown` event) must run **before** step 3 or be migrated to a
non-publishing form. The contract is enforced by an assertion in `drain!`:
if any publish lands during drain, the result is downgraded to `:ok? false
:reason :late-publish`.

### Tests for BD-2a

1. **Sink drain happy path.** Publish N events; `drain!` reports
   `:drained-count N`, all N durable on disk.
2. **Late publisher quiesced.** Background thread loops on `publish!`.
   Call `quiesce!` then `drain!`. Assert post-quiesce publishes return
   the rejection result, no late event lands.
3. **Late publisher without quiesce regresses.** Verify that calling
   `drain!` alone does *not* cover late publishers — pins the contract.
4. **Slow sink hits timeout.** Inject 10s sink with 1s timeout; assert
   `:reason :timeout`, `:pending-count` matches, headless would exit
   non-zero.
5. **Sink write failure.** Inject failing sink; assert
   `:reason :sink-error`, `:failed-events` and `:failed-sinks` populated.
6. **Pipeline exception.** `execute-workflow-pipeline` throws after
   publishing N events; assert failure event + the N prior events all
   drain successfully.
7. **Cleanup-publishes-event invariant.** Inject cleanup that publishes
   after `drain!` starts; assert `:reason :late-publish` in result.
8. **Atomic file write.** File sink uses `temp + fsync(file) + rename +
   fsync(parent dir)` where supported. Verify by killing the process
   mid-write and checking that no half-written event file is read on
   startup.

---

## BD-2b — Retention, archival, and the cleanup hook

### Manifest + lease liveness model

Every workflow directory carries a `manifest.json`:

```json
{
  "workflow_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "schema_version": "1.0.0",
  "status": "active",
  "created_at": "2026-05-07T03:00:00Z",
  "completed_at": null,
  "archived_at": null,
  "snapshot_watermark": {
    "workflow_sequence_number": 0,
    "last_event_id": null
  },
  "raw_events_retained_until": null,
  "owner": {
    "pid": 12345,
    "host": "hostname",
    "lease_renewed_at": "2026-05-07T03:00:30Z",
    "lease_ttl_seconds": 60
  }
}
```

The state machine:

```text
active ─┬─ completed ─┐
        ├─ failed ────┼─▶ archiving ─▶ archived ─▶ tombstoned
        └─ cancelled ─┘
        (crashed: detected by slow path; archives via replay-synthesis)
```

Liveness rule: a workflow is *alive* iff
`status == "active"` AND its lease has been renewed within
`lease_ttl_seconds`. A workflow that has not renewed within the TTL **and
is owned by a dead process** (PID gone, or `host` matches and
`/proc/{pid}` is gone — fall back to age threshold on Windows) is
classified as `crashed` by the slow path.

This replaces the v1 "newest event older than TTL = crashed" rule, which
misclassifies legitimately quiet active workflows.

### Two-tier retention

| Tier | Default | Knob |
|---|---|---|
| Raw archived event tail | 30 d | `MINIFORGE_EVENTS_ARCHIVED_TAIL_RETENTION_DAYS` |
| Workflow summary / snapshot | forever | `MINIFORGE_EVENTS_SUMMARY_RETENTION_DAYS` (set to integer to bound) |
| Cleanup cadence | 60 min | `MINIFORGE_EVENTS_CLEANUP_INTERVAL_MINUTES` |
| Crashed-workflow detection threshold | lease TTL × 2 | `MINIFORGE_EVENTS_CRASHED_THRESHOLD_SECONDS` |

When raw event tail expires, raw events are deleted but the snapshot
and summary stay. The workflow transitions `archived → tombstoned`.
Tombstoned workflows still hydrate from snapshot on consumer cold start.

### Atomic archive operation

```text
1. Inside live/{wid}: write snapshot to snapshot.json.tmp.
2. fsync(snapshot.json.tmp).
3. Update manifest.json status="archiving", snapshot_watermark_sequence=N.
4. fsync(manifest.json) and parent dir.
5. mv snapshot.json.tmp → snapshot.json.
6. mv live/{wid} → archived/{wid} (atomic rename on same FS).
7. Touch archived/{wid}/archived.marker.
8. fsync(archived/) and archived/{wid}/.
```

Crash recovery: any `live/{wid}` whose `manifest.status == "archiving"`
or that is missing `archived.marker` after the rename is recovered by
re-running steps 5–8. Idempotent.

The cross-FS case (e.g. archived to a different volume) loses atomic
rename; we keep archive on the same FS to preserve atomicity. Operators
who want off-volume archive can run a separate copy job after
`tombstoned`.

### Tests for BD-2b

1. **Quiet active workflow not archived.** Workflow active, lease
   renewed, no events for > TTL. Cleanup leaves it alone.
2. **Dead-lease crashed.** Active manifest, lease expired, owner pid
   gone. Cleanup classifies as crashed and archives via slow-path
   replay (BD-2c interaction).
3. **Completed workflow archives atomically.** Drop process between
   each archive step; restart cleanup; result is identical to clean
   archive.
4. **Late publish post-terminal rejected.** After `:workflow/completed`
   plus quiesce, attempt to publish. Assert rejection.
5. **Idempotent cleanup.** Run cleanup twice; counts stable; no dup
   archives.
6. **Concurrent consumer read.** Consumer tails `live/{wid}` while
   archive rename runs. Consumer sees either live or archived, never
   half-state. Verified by re-reading the workflow path on the
   `archived.marker` watch.
7. **Tombstone preserves summary.** Set
   `MINIFORGE_EVENTS_ARCHIVED_TAIL_RETENTION_DAYS=0`; run cleanup;
   verify raw events gone, snapshot and manifest still present, status
   `tombstoned`.
8. **Operator-invoked cleanup.** Synchronous CLI invocation reports
   structured counts: `{:archived N :tombstoned M :events-deleted K
   :bytes-freed B}`.

---

## BD-2c — Snapshots, active checkpoints, and bounded replay

### Snapshot emission paths

Three triggers, all writing the same on-disk shape:

1. **Terminal archive snapshot.** On `:workflow/completed | :failed |
   :cancelled` followed by `quiesce!`, the supervisory-state component
   serializes its slice of the entity table for that workflow to
   `live/{wid}/snapshot.json`, then BD-2b's atomic archive moves it.
2. **Active checkpoint.** Every N events or M minutes (whichever
   comes first; defaults `N=500`, `M=2`), the component writes
   `live/{wid}/checkpoint.json` carrying the current entity-table
   slice plus watermark. Atomic via temp + rename.
3. **Crashed slow-path synthesis.** When BD-2b classifies a workflow
   as crashed and it has no live snapshot, the cleanup process replays
   the workflow's retained events in a fresh accumulator, writes
   `snapshot.json`, then archives. If replay fails (corrupted events),
   the archive completes without a snapshot and the manifest carries
   `snapshot_watermark.last_event_id: null`. Consumer hydrates raw
   events only.

### Snapshot schema

Snapshot files are *not* events. Distinct envelope, distinct top-level
type field, so a malformed reader can never confuse them:

```clojure
{:snapshot/version       "1.0.0"
 :snapshot/type          :workflow
 :snapshot/workflow-id   #uuid "..."
 :snapshot/captured-at   #inst "..."
 :snapshot/watermark
   {:workflow-id              #uuid "..."
    :workflow-sequence-number 1234
    :last-event-id            "018f3a9c8e4b12d0"
    :timestamp                #inst "..."}
 :snapshot/entities
   {:workflow-run    {...}
    :agents          [{...}]
    :prs             [{...}]
    :decisions       [{...}]
    :tasks           [{...}]
    :attention-items [{...}]}}
```

Versioning: readers reject `:snapshot/version` they don't understand.
Forward compatibility is via additive entity keys — readers tolerate
unknown keys under `:snapshot/entities`. Schema bumps are explicit and
require a migration plan.

### Bounded-replay correctness

The accumulator is a pure reducer over an entity table; replaying
events from sequence `N+1` after loading a snapshot at sequence `N`
must produce the same entity table as replaying from zero. Property
test:

```clojure
(deftest snapshot-replay-equivalence
  (forall [events  (gen events)
           split-at (gen sequence-number-in-range)]
    (let [full       (replay-from-zero events)
          prefix     (replay-from-zero (take split-at events))
          suffix     (drop split-at events)
          via-snap   (-> prefix
                         entities->snapshot
                         transit-roundtrip
                         snapshot->entities
                         (replay-events suffix))]
      (is (= full via-snap)))))
```

The `transit-roundtrip` step is critical — it pins serialization
correctness, not just reducer associativity. The same fixture
deserializes in Rust (BD-2c consumer side) and asserts entity-table
parity.

### Consumer hydration (Rust, downstream PR)

Cold start in `miniforge-control`:

```text
1. Load summaries/index.json (a small file listing all workflows
   with their status, archive path, and snapshot pointer).
2. For each active workflow:
     a. Load live/{wid}/checkpoint.json if present, else hydrate empty.
     b. Read live/{wid}/events with sequence > checkpoint watermark.
3. For archived workflows within UX-K history window:
     a. Lazily hydrate from archived/{wid}/snapshot.json on demand
        (when the user opens the run in the dossier).
     b. Eagerly load only lightweight summary fields for the runs list.
4. Tombstoned workflows: hydrate from snapshot only; raw events gone.
5. Older archived workflows (outside UX-K window): omit from startup,
   hydrate on explicit open.
```

The eager-load avoidance is what fixes the cold-start cost. With
thousands of completed workflows, hydrating each `snapshot.json`
synchronously reproduces the cold-start problem at a different layer.
The summaries index is the cheap top-level structure the TUI rebuilds
its workflow list from.

### Tests for BD-2c

1. **Property: snapshot equivalence.** As above, `forall` over event
   sequences and split points.
2. **Cross-language fixture parity.** A canonical snapshot fixture is
   checked in to both `miniforge` and `miniforge-control`. Both
   languages decode it; entity-by-entity assertions match.
3. **Active checkpoint cadence.** Publish 1500 events; verify three
   checkpoints written at sequences ~500/1000/1500, each watermark
   correct.
4. **Crashed-workflow synthesis.** Kill mid-pipeline; restart cleanup;
   verify slow-path replays retained events into a snapshot, manifest
   transitions `crashed → archived`.
5. **Crashed with corrupted tail.** Inject a malformed event file;
   verify cleanup completes archive *without* a snapshot, manifest
   carries `snapshot_watermark.last_event_id: null`, consumer falls
   back to raw-event replay (and surfaces "incomplete history" in the
   dossier).
6. **Unknown snapshot version rejected.** Bump `:snapshot/version` to
   `2.0.0`; verify reader logs and skips, falls back to event replay.
7. **Forward-compatible entities.** Add a junk key under
   `:snapshot/entities`; verify reader hydrates known keys and ignores
   the unknown one.

---

## Cross-cutting: the on-disk contract

This is the actual cross-repo interface. Both `miniforge` and
`miniforge-control` MUST agree on:

### Directory layout

```text
~/.miniforge/events/
  live/
    {workflow-id}/
      manifest.json
      checkpoint.json            # may be absent before first checkpoint
      events/
        {seq:020d}__{ts}__{uuid}.json
  archived/
    {workflow-id}/
      manifest.json              # status: archived | tombstoned
      snapshot.json              # may be null on crashed/no-snapshot
      archived.marker
      events/                    # absent after tombstone
        {seq:020d}__{ts}__{uuid}.json
  summaries/
    index.json                   # top-level workflow list, cheap to read
```

### Sortable event IDs and filename grammar

Each event gets a new `:event/id` on the envelope, generated by a
local Snowflake-style ID generator:

- 41-bit UTC millisecond timestamp from a fixed miniforge epoch
  (`2026-01-01T00:00:00Z`),
- 10-bit local worker id (1024 concurrent workers per host),
- 12-bit per-worker sequence within the millisecond (4096 events / ms
  / worker).

The id serializes as fixed-width 16-character lowercase hex (64-bit
value).

The existing `:event/sequence-number` keeps its meaning: per-workflow
logical order, used by the accumulator to verify contiguous replay.

```text
events/{event-id-hex16}__{workflow-seq-dec12}.transit.json
```

Example: `events/018f3a9c8e4b12d0__000000012345.transit.json`.

- `event-id-hex16`: 16-char lowercase hex Snowflake ID. Lexical sort
  matches creation order across all workflows.
- `workflow-seq-dec12`: zero-padded 12-digit decimal per-workflow
  sequence. 12 digits is comfortable headroom (10¹² events per
  workflow); the field is not load-bearing for sort, only for
  workflow-replay verification at read time.
- Delimiter `__` cannot appear in either field.
- Extension `.transit.json` makes the wire format explicit; current
  files use bare `.json` and a Transit payload, which is ambiguous.

#### Clock rollback and worker-id leasing

Snowflake IDs generated locally without a central authority need two
defenses:

- **Clock rollback** is handled by a persisted local high-water mark
  stored at `~/.miniforge/events/.snowflake-hwm`. The generator refuses
  to emit an id with `(epoch-ms, worker, seq)` that would sort before
  the high-water mark. On a detected rollback, the generator stalls
  until wall clock catches up, with a logged warning. (A user
  intentionally setting their clock back days will see workflow
  startups block; that is preferable to silent ID reuse.)
- **Worker-id allocation** is lease-based. A worker takes a lease on
  `~/.miniforge/events/.workers/{worker-id}.lease` (file flock + pid
  ownership). On startup, a worker claims the lowest free id 0..1023.
  Stale leases (process gone, host matches, > 60 s old) are reclaimable.
  Two miniforge processes on the same host get different worker ids;
  the rare cross-host case is out of scope (single-machine non-goal).

Both invariants have property tests in BD-2a's coverage.

#### Backwards compatibility

Readers accept three filename shapes during migration:

1. Legacy: `{ts}-{uuid}.json` (current writes).
2. v2 transitional: `{seq:020d}__{ts}__{uuid}.json` (only if any
   already wrote in this format — none should yet).
3. v3 target: `{event-id-hex16}__{workflow-seq-dec12}.transit.json`.

Legacy files are sorted by mtime and require payload parsing to recover
their sequence number. They are migrated lazily — never rewritten in
place — and disappear naturally as workflows complete and archive
under the v3 grammar.

### Range query complexity (corrected, again)

Snowflake IDs in filenames let readers **skip old files without
parsing payloads**. They do **not** by themselves make directory
enumeration cheaper — that remains `O(files-in-dir)`.

For workflows with O(10⁴) events the no-parse win is already a >10×
cold-start improvement, which is the actual pain point. True
`O(events-in-window)` requires one of:

- bucketed subdirectories keyed off the event-id timestamp prefix
  (`events/018f3a/{event-id}__{seq}.transit.json`),
- a per-workflow index file (`index.sqlite` or `index.json`
  mapping event-id → filename / offset),
- an append-only per-workflow log with a separate index.

None are in this RFC's scope. If post-implementation measurement shows
directory enumeration is the new bottleneck, the smallest compatible
follow-on is bucketed directories — the bucket can be derived from the
filename without changing the on-disk contract for any individual
file.

### Manifest schema (cross-repo source of truth)

See BD-2b. Both repos validate against the same JSON Schema fixture
checked into `contracts/event-stream/manifest.schema.json` (new path).

### Snapshot schema (cross-repo source of truth)

See BD-2c. Same pattern: JSON Schema fixture under
`contracts/event-stream/snapshot.schema.json`.

### Test fixtures

A small set of canonical fixtures live under `contracts/event-stream/fixtures/`:

```text
fixtures/
  snapshot-minimal.json
  snapshot-full.json
  manifest-active.json
  manifest-archiving.json
  manifest-archived.json
  manifest-tombstoned.json
  manifest-crashed.json
  events-window-tail.json
```

Both Clojure and Rust test suites consume these. A change in either
language that breaks a fixture round-trip fails CI in both repos.

---

## Sequencing

```text
BD-2a (quiesce + drain)
   │
   ├──▶ BD-2b (manifest + atomic archive + 2-tier retention + filename migration)
   │      │
   │      ├──▶ BD-2c upstream (snapshots + active checkpoints + slow-path synthesis)
   │      │      │
   │      │      └──▶ BD-2c consumer (Rust hydration + summaries index)
   │      │
   │      └──▶ BD-3 (consumer-side surfacing of historical investigation)
```

- **BD-2a first.** Without quiesce + drain, retention semantics are
  undefined and snapshot watermarks are unreliable. Smallest scope,
  highest leverage on correctness.
- **BD-2b next.** Establishes the manifest, the atomic archive
  contract, and the filename grammar that BD-2c depends on.
- **BD-2c upstream and consumer in parallel** once BD-2b's filename
  migration lands. Snapshot fixtures are the synchronization point.
- **BD-3** becomes implementable once snapshots and summaries index
  exist.

## Open questions

1. **Active checkpoint cadence.** Defaults `N=500` events / `M=2 min`.
   Pending real-workload measurement; may need to be lower for chatty
   workflows.
2. **Snapshot summary scope.** Does the summaries index carry just
   workflow status + spec title (cheap, what BD-1 enables), or also
   counts (PRs, decisions, tasks)? Counts make the runs list richer at
   startup but enlarge the index. Recommend status + spec only;
   counts come from the (lazily-loaded) snapshot.
3. **Crashed-workflow snapshot synthesis cost.** Replaying retained
   events at archival time pays the O(events) cost once, not on every
   cold start, but it can still spike during cleanup. Should slow-path
   archival be batched / rate-limited, or run inline? Recommend inline
   with a per-workflow timeout that, on miss, archives without
   snapshot.
4. **`MINIFORGE_BEST_EFFORT_SHUTDOWN`.** Provided for CI / dev loops
   that don't care about durability. Default off. Keep, or push back?
5. **Index file vs current layout.** This RFC does not propose an
   index file. If post-implementation measurement shows directory
   enumeration is the new bottleneck, follow-on work adds
   `live/{wid}/events/index.json` mapping seq → filename. Pre-deciding
   would over-build.

## Tracking

This RFC corresponds to **BD-2** in
`miniforge-control/work/in-progress/2026-04-25-console-ux-pass-dag.md`.
Each sub-item lands as its own PR(s):

- `BD-2a` — single upstream PR (`event-stream/quiesce!` +
  `event-stream/drain!` + `run-workflow!` call site + tests).
- `BD-2b` — single upstream PR (manifest, atomic archive, scheduled
  cleanup, filename migration, two-tier retention).
- `BD-2c` — paired PRs:
  - Upstream: snapshot emission, active checkpoints, slow-path
    synthesis, snapshot/manifest schemas.
  - Consumer (`miniforge-control`): `WorkflowSnapshot` /
    `WorkflowManifest` types, summaries index reader, lazy snapshot
    hydration.
- Cross-repo fixtures live under `contracts/event-stream/` and ship
  with BD-2c upstream.
