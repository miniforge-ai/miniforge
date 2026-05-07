# Event-stream replay, retention, and quiesce (BD-2)

Status: **draft v4**, opened 2026-05-07, revised three times 2026-05-07
after design review. Author: Christopher Lester (`miniforge-control`
BD-2).

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

## v4 changes vs v3

Open questions resolved into commitments. Two material guardrails
added:

- **Crashed workflows whose snapshot synthesis fails or times out are
  now archived in a *degraded* state**, not "archived without snapshot."
  Manifest carries `archive_status` + `snapshot_status` and the slow
  path protects the raw event tail from ordinary retention deletion
  while `snapshot_status` is `pending` or `failed`. Without this rule,
  a crashed workflow could become neither hydratable nor replayable —
  the v3 wording opened that hole.
- **`MINIFORGE_BEST_EFFORT_SHUTDOWN`** kept as an explicit local/dev
  opt-out, default off, but reframed as a noisy degradation: drain
  failures still log structured warnings, the run result still carries
  `event_durability` metadata, and CI correctness paths must not enable
  it.

Other v4 changes:

- Active checkpoint cadence formalized as **OR semantics** (events OR
  time, dirty-only, coalesced, terminal forces final) with explicit
  config knobs and measurement hooks.
- Summary index field list pinned: cheap navigation metadata only,
  counts deferred to lazy snapshot load.
- Cleanup work explicitly **budgeted per pass** with knobs to prevent
  retention from becoming a startup/shutdown latency bug.
- Future-index language softened: avoid frequently rewritten
  `index.json`; prefer append-only or sparse if measurement justifies
  it. Add measurement hooks now so the decision is data-driven.

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
9. **Best-effort shutdown — timeout.** Set
   `MINIFORGE_BEST_EFFORT_SHUTDOWN=1`, slow sink hits timeout, workflow
   succeeded. Assert exit zero, run result carries
   `event_durability: "best_effort_timeout"` and `undrained_event_count`.
10. **Best-effort shutdown — sink failure.** Same flag, sink write
    fails. Assert structured `failed_sinks` populated; exit zero only
    if workflow itself succeeded (failure cases still exit non-zero).
11. **Best-effort never silent.** Run with the flag set; assert at
    least one structured warning is logged on every drain
    timeout / sink failure.

---

## BD-2b — Retention, archival, and the cleanup hook

### Manifest + lease liveness model

Every workflow directory carries a `manifest.json`:

```json
{
  "workflow_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "schema_version": "1.0.0",
  "status": "active",
  "archive_status": "live",
  "snapshot_status": "none",
  "snapshot_failure_reason": null,
  "created_at": "2026-05-07T03:00:00Z",
  "completed_at": null,
  "archived_at": null,
  "snapshot_watermark": {
    "workflow_sequence_number": 0,
    "last_event_id": null
  },
  "raw_events_retained": true,
  "raw_events_retained_until": null,
  "owner": {
    "pid": 12345,
    "host": "hostname",
    "lease_renewed_at": "2026-05-07T03:00:30Z",
    "lease_ttl_seconds": 60
  }
}
```

Field semantics:

- `status` — workflow lifecycle (active / completed / failed /
  cancelled / crashed).
- `archive_status` — storage lifecycle (live / archiving / archived /
  tombstoned).
- `snapshot_status` — snapshot availability (none / available /
  pending / failed). `pending` means synthesis was attempted and
  timed out; `failed` means it errored.
- `snapshot_failure_reason` — populated when `snapshot_status` is
  `pending` or `failed` (e.g. `"timeout"`, `"corrupted-tail"`).
- `raw_events_retained` — when true, the slow path **must not** delete
  this workflow's raw event tail under ordinary retention rules. Set
  true on crashed workflows whose snapshot is `pending`/`failed` so
  the only reconstructable state is preserved. A separate explicit
  knob (default off) controls lossy crashed-workflow retention.

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
| Workflow summary / snapshot | forever | `MINIFORGE_EVENTS_SUMMARY_RETENTION_DAYS` (integer to bound) |
| Cleanup cadence | 60 min | `MINIFORGE_EVENTS_CLEANUP_INTERVAL_MINUTES` |
| Crashed-workflow detection threshold | lease TTL × 2 | `MINIFORGE_EVENTS_CRASHED_THRESHOLD_SECONDS` |
| Crashed snapshot synthesis timeout | 10 s | `MINIFORGE_EVENTS_CRASH_SNAPSHOT_TIMEOUT_SECONDS` |
| Cleanup workflows per pass | 10 | `MINIFORGE_EVENTS_CLEANUP_MAX_WORKFLOWS_PER_PASS` |
| Cleanup wall-time per pass | 30 s | `MINIFORGE_EVENTS_CLEANUP_MAX_SECONDS_PER_PASS` |

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
2. **Active checkpoint.** The component writes
   `live/{wid}/checkpoint.json` carrying the current entity-table
   slice plus watermark. Atomic via temp + rename. Cadence:

   ```text
   write checkpoint when:
     workflow is dirty
     AND (
       events_since_last_checkpoint >= CHECKPOINT_EVERY_EVENTS
       OR seconds_since_last_checkpoint >= CHECKPOINT_EVERY_SECONDS
     )
   ```

   Defaults: `CHECKPOINT_EVERY_EVENTS=500`,
   `CHECKPOINT_EVERY_SECONDS=120`. OR semantics — either threshold
   is sufficient, idle workflows produce no writes.

   Coalescing: **at most one checkpoint write per workflow in flight**.
   If another checkpoint is requested while one is running, the
   request is coalesced into a single follow-up write, not queued.

   Terminal events (`:workflow/completed | :failed | :cancelled`)
   force a final checkpoint regardless of cadence, as does clean
   shutdown when active state is dirty.

   Knobs:

   - `MINIFORGE_EVENTS_CHECKPOINT_EVERY_EVENTS` (default `500`)
   - `MINIFORGE_EVENTS_CHECKPOINT_EVERY_SECONDS` (default `120`)
3. **Crashed slow-path synthesis.** When BD-2b classifies a workflow
   as crashed and it has no live snapshot, the cleanup process attempts
   to replay the workflow's retained events in a fresh accumulator and
   write `snapshot.json` before archiving. The attempt is bounded by
   `MINIFORGE_EVENTS_CRASH_SNAPSHOT_TIMEOUT_SECONDS` (default 10 s).

   Three outcomes, all of which **still archive** the workflow:

   | Outcome | `snapshot_status` | Raw events kept? |
   |---|---|---|
   | Replay succeeds within timeout | `available` | per normal retention |
   | Replay times out | `pending` | yes — protected from tail deletion |
   | Replay errors (corrupted events, schema mismatch) | `failed` | yes — protected from tail deletion |

   When `snapshot_status` is `pending` or `failed`, the manifest's
   `raw_events_retained: true` overrides the ordinary tail-deletion
   rule. A later cleanup pass may retry synthesis; on success it
   promotes `pending → available` and the raw tail returns to normal
   retention.

   This guardrail closes a v3 hole where a crashed workflow could
   become neither hydratable (no snapshot) nor replayable (raw tail
   deleted at 30 d).

   Headless shutdown does **not** synthesize crashed-workflow
   snapshots inline — only quiesce/drain and active-workflow
   checkpoint writes run there. Crashed synthesis happens in the
   scheduled cleanup pass, bounded by per-pass workflow and wall-time
   budgets so it can't turn into a startup latency bug.

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
6. Workflows with `snapshot_status: "pending" | "failed"`: surface as
   "incomplete history" in the runs list and, on dossier open, replay
   the retained raw event tail to reconstruct state on the fly. The
   raw_events_retained guard guarantees the tail is still present.
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
3. **Active checkpoint cadence — events.** Publish 1500 events
   quickly; verify three checkpoints at ~500/1000/1500.
4. **Active checkpoint cadence — time.** Publish a single event,
   wait `CHECKPOINT_EVERY_SECONDS + ε`; verify a second checkpoint
   was written at the time threshold even though the event count was
   well under 500.
5. **Active checkpoint — idle clean produces nothing.** Workflow
   exists but emits no new events; assert no checkpoint writes.
6. **Active checkpoint coalescing.** Force two cadence triggers to
   fire while a previous checkpoint write is in flight; assert
   exactly one follow-up write happens, not two.
7. **Active checkpoint — terminal forces final.** Publish 100 events
   (under `CHECKPOINT_EVERY_EVENTS`), then `:workflow/completed`;
   assert a final checkpoint is written before archive.
8. **Crashed-workflow synthesis.** Kill mid-pipeline; restart cleanup;
   verify slow-path replays retained events into a snapshot, manifest
   transitions `crashed → archived` with
   `snapshot_status: "available"`.
9. **Crashed synthesis timeout.** Force replay to exceed the
   crash-snapshot timeout; verify archive completes,
   `snapshot_status: "pending"`, `raw_events_retained: true`, and
   that ordinary tail-retention cleanup leaves the raw events alone.
10. **Crashed synthesis retry promotes pending.** Run a second
    cleanup pass with the timeout raised; verify the workflow's
    `snapshot_status` transitions `pending → available` and
    `raw_events_retained` returns to false.
11. **Crashed with corrupted tail.** Inject a malformed event file;
    verify cleanup archives with `snapshot_status: "failed"`,
    `snapshot_failure_reason: "corrupted-tail"`, raw events preserved,
    consumer falls back to raw-event replay (and surfaces "incomplete
    history" in the dossier).
12. **Unknown snapshot version rejected.** Bump `:snapshot/version` to
    `2.0.0`; verify reader logs and skips, falls back to event replay.
13. **Forward-compatible entities.** Add a junk key under
    `:snapshot/entities`; verify reader hydrates known keys and
    ignores the unknown one.

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

### Summary index entry shape

The runs-list accelerator. **Cheap navigation metadata only**; counts
and aggregates are deliberately out of scope for v1 and come from the
lazily-loaded snapshot.

```json
{
  "workflow_id": "f47ac10b-...",
  "status": "active|completed|failed|crashed|cancelled|archived",
  "spec_id": "...",
  "spec_title": "...",
  "created_at": "2026-05-07T03:00:00Z",
  "completed_at": null,
  "archived_at": null,
  "snapshot_path": "archived/f47ac10b-.../snapshot.transit.json",
  "snapshot_status": "available|pending|failed|none",
  "snapshot_version": "1.0.0",
  "last_event_id": "018f3a9c8e4b12d0",
  "workflow_sequence_number": 12345
}
```

Out of scope for v1 (intentional):

- PR / decision / task / attention / agent counts.
- Token / cost totals.
- Phase timing aggregates.

These belong in the lazily-loaded workflow snapshot. The runs list
shows a cheap row immediately and enriches on focus / open. One
exception worth flagging: if the runs list later needs a single
"attention required" boolean at startup, that one denormalized field
might be worth carrying. Hold off until the UI demonstrably needs it.

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
cold-start improvement, which is the actual pain point.

True `O(events-in-window)` is deferred to follow-on work. Possible
shapes, in rough order of preference:

- **Bucketed subdirectories** keyed off the event-id timestamp prefix
  (`events/018f3a/{event-id}__{seq}.transit.json`). Smallest contract
  change — bucket derives from filename without per-file metadata.
- **Sparse per-workflow index** — every K events, write a record
  `{seq, event_id, filename}` to an append-only `index.ndjson`. Reader
  scans forward from the nearest indexed point. Avoids
  write-amplification of a per-event index.
- **Append-only per-workflow log + index** — restructure to one log
  file per workflow with byte offsets. Bigger storage-shape change.
- **SQLite per workflow** — strongest range semantics, biggest
  decision.

A frequently rewritten `index.json` mapping every event is **not**
recommended — write amplification and corruption hotspot.

### Measurement hooks (in BD-2 scope)

So the index decision is data-driven, the reader emits these counters
in BD-2 itself:

- `directory_entries_listed`
- `files_opened`
- `payloads_parsed`
- `skipped_by_filename_watermark`
- `replay_wall_time_ms`
- `snapshot_write_ms` / `snapshot_size_bytes` (per active checkpoint)
- `suffix_replay_ms` / `suffix_replay_events` (per workflow on cold
  start)

Logged at debug, summarized at info on cold-start completion. A
follow-on RFC that proposes any of the index shapes above must cite
these numbers from production telemetry, not vibe.

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

## Decisions (resolved from v3 open questions)

### Active checkpoint cadence

Active workflows checkpoint when **dirty** and either 500 events or
2 minutes have elapsed since the previous checkpoint (OR semantics, not
AND). Idle clean workflows produce no writes. Terminal events force a
final checkpoint before archival regardless of cadence. Coalesced so
at most one checkpoint write per workflow is in flight; an overlapping
request becomes a single follow-up write, not a queue. Defaults are
configurable
(`MINIFORGE_EVENTS_CHECKPOINT_EVERY_EVENTS`,
`MINIFORGE_EVENTS_CHECKPOINT_EVERY_SECONDS`) and will be revisited
after measurement of real event volume, snapshot size, and startup
suffix-replay time.

### Summary index scope

The startup summaries index contains only cheap navigation metadata:
workflow id, status, spec id/title, timestamps, snapshot pointer,
snapshot status, version, and watermarks (see schema in the on-disk
contract section). Entity counts are intentionally excluded from v1
and come from the lazily-loaded workflow snapshot. The single
attention-required boolean is the one denormalization candidate to
revisit if the UI needs it.

### Crashed-workflow snapshot synthesis

Slow-path archival attempts to synthesize a crashed-workflow snapshot
inline by replaying that workflow's retained event tail. Bounded by
per-workflow timeout (default 10 s) and per-cleanup-pass workflow /
wall-time budgets. **All three outcomes archive the workflow** with
explicit `snapshot_status` (`available` / `pending` / `failed`); the
`pending` and `failed` states protect the raw event tail from ordinary
deletion via `raw_events_retained: true`, so the workflow remains
reconstructable. A later cleanup pass may retry synthesis and promote
`pending → available`. Headless shutdown does not synthesize crashed
snapshots inline.

### Best-effort shutdown

`MINIFORGE_BEST_EFFORT_SHUTDOWN` is supported as an explicit opt-out
for local/dev loops. **Off by default.** Normal headless mode exits
non-zero on drain timeout or required sink failure; that is the BD-2a
correctness contract and CI must not weaken it. Best-effort mode
still attempts quiesce + drain, still logs structured warnings, and
still reports degradation in the machine-readable run result:

```json
{
  "workflow_status": "completed",
  "event_durability": "best_effort_timeout",
  "undrained_event_count": 17,
  "failed_sinks": ["file"]
}
```

It exits zero only when (a) `MINIFORGE_BEST_EFFORT_SHUTDOWN=1` is set
and (b) the workflow result itself was successful. Sink failure is
never silent.

### Index file

BD-2 does not introduce an index file. Sortable event IDs in
filenames let the reader skip payload parsing for events below the
snapshot watermark, but directory enumeration remains proportional to
files in the workflow directory. Measurement hooks are in BD-2 scope
so a follow-on RFC can justify any index shape from data, not vibe.
The deferred shape preference order is bucketed dirs → sparse
append-only index → restructured log/SQLite. A frequently-rewritten
per-event `index.json` is explicitly **not** recommended — write
amplification and corruption hotspot.

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
