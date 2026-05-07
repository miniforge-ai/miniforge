# Event-stream replay, retention, and quiesce (BD-2)

Status: **draft**, opened 2026-05-07. Author: Christopher Lester (`miniforge-control` BD-2).

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
  the accumulator, not snapshot input. This asymmetry is what BD-2c has to
  resolve.

The actual replay pain lives in the consumer: `miniforge-control`'s Rust
TUI tails the event directory and rebuilds its own entity table on every
start. So the snapshot/bounded-replay design has to span both repos.

## Goals

1. `--headless` exit guarantees all events the pipeline produced are
   durable on disk before the process returns.
2. The on-disk event log has bounded, predictable size with retention
   policy controlled by an explicit knob, not a side effect of sink
   construction.
3. Cold-start replay cost on the consumer is bounded by a configurable
   window, not by total history. A workflow that completed a year ago
   does not pay startup cost today.
4. Old workflow context is still investigable — retention does not mean
   amnesia. Completed workflows produce a durable summary the consumer
   can hydrate without holding the full event tail.

## Non-goals

- Changing the event envelope shape (`:event/version "1.0.0"`).
- Cross-host replay or distributed event log. Stays single-machine,
  single-user.
- Retroactive snapshotting of the existing 60k-event log on
  developers' disks — the consumer's `UX-K` window is the migration
  story for already-resident history.

---

## BD-2a — Quiesce on headless exit

**Race window.** `bases/cli/src/.../workflow_runner.clj:781-810`
returns from `run-workflow!` as soon as `execute-workflow-pipeline`
completes. `event_stream/core.clj:104-142` `publish!` is fire-and-forget;
late events from background producer threads (agent heartbeats, phase
cleanup) can land after `run-workflow!` has returned and the process is
already tearing down. There is no barrier between "pipeline done" and
"all events durable".

**Proposal.**

1. Add `event-stream/drain!` to the component interface. Semantics:
   block until every event accepted by `publish!` before the call has
   been written to all configured sinks (and fsynced where the sink
   supports it). Returns once the in-flight queue is empty.
2. Give `drain!` a bounded timeout (default 5 s; configurable). On
   timeout, log a warning naming the unflushed sink(s) and the
   approximate inflight count, then proceed with shutdown. Timing out is
   a soft failure — better than hanging headless runs forever.
3. Call `drain!` from `run-workflow!` after the pipeline completes and
   before the `progress-cleanup` finally block. Independent of `quiet`
   / dashboard mode — quiesce is a correctness contract, not a display
   concern.

**Interface change.** Additive — existing callers keep working without
the explicit drain; only headless and CLI exit paths gain the barrier.

**Tests.** Inject a slow sink, publish N events, call `drain!`, assert
all N landed. Inject a hanging sink, assert `drain!` returns within
timeout and logs the warning.

---

## BD-2b — Retention, archival, and the cleanup hook

**Today.** `cleanup-stale-events!` (`sinks.clj:115-159`) is the only
retention path. Two structural problems:

- Runs once per sink construction in a future. A long-lived headless
  daemon never re-evaluates retention.
- Operates by file mtime, not by workflow boundary. A still-active
  workflow with a slow phase can have its early events evicted at the
  TTL line.

**Proposal.** Two-layer retention with explicit boundaries:

1. **Workflow-completion archival (fast path).** On `:workflow/completed`
   or `:workflow/failed`, emit a per-workflow snapshot file (see BD-2c)
   and move the workflow's event files into an `archived/` subdirectory
   adjacent to the live directory. After M days (configurable, default
   30 d), the archive is deletable. The supervisory-state accumulator
   already detects completion, so the trigger lives there; the archive
   move lives in the sink.
2. **TTL fallback (slow path).** Keep `cleanup-stale-events!` for
   workflows that died without a completion event (process crash,
   power loss). Promote it from "future on sink creation" to a
   **scheduled** job: invoke on a fixed cadence (default 1 h), and
   expose it as a callable on the event-stream interface so operators
   and the CLI can invoke it explicitly. Operate by *workflow*, not
   per file: a workflow whose newest event is older than the TTL is
   treated as crashed and archived as a unit.

**Knobs (env or config):**

- `MINIFORGE_EVENTS_ARCHIVE_AFTER_DAYS` (default `30`).
- `MINIFORGE_EVENTS_RETENTION_HOURS` (default `168` / 7 d, current
  default).
- `MINIFORGE_EVENTS_CLEANUP_INTERVAL_MINUTES` (default `60`).

**Consumer impact.** When the consumer reads a workflow directory and
finds it has been archived, it should look in `archived/` for the
snapshot summary plus any retained event tail and hydrate from there.
This is the BD-3 surface (retained historical investigation depth) —
this RFC just guarantees the snapshot + tail exist.

**Tests.** Verify:

- Active workflow's events are not evicted regardless of file mtime.
- Completed workflow gets its snapshot written and event files moved.
- Crashed workflow (no completion event, last event older than TTL) is
  archived by the slow path.
- Operator-invoked cleanup is synchronous and reports counts.

---

## BD-2c — Snapshots and bounded replay

**Today.** Cold start reads every JSON file in the directory and
re-folds the entity table. The accumulator is a pure reducer
(`supervisory_state/accumulator.clj:19-34`), so snapshotting is
mechanically straightforward; the gap is that no snapshot input exists
and the reader has no range API.

**Proposal — three pieces:**

1. **Snapshot emission (producer side).** When a workflow archives
   (BD-2b), serialize the accumulator's projection of that workflow's
   slice of the entity table into
   `archived/{workflow-id}/snapshot.json` (Transit-JSON, same envelope
   as events for format reuse). The snapshot carries:
   - the entity-table slice for that workflow (workflow-run, agents,
     prs, decisions, tasks, attention items),
   - a watermark `{:workflow-id uuid :sequence-number N :timestamp T}`
     marking the last event subsumed,
   - a `:snapshot/version` field for forward compatibility.
2. **Reader range API.** Add
   `event_stream/reader/read-workflow-events-since(workflow-id, seq-no)`
   so consumers can skip events already covered by a snapshot. The
   sequence number comes off the event envelope; persist it in the
   filename so range scans don't have to parse every payload.
   Suggested filename: `{ts}-{seq}-{uuid}.json`.
3. **Consumer hydration (Rust, downstream).** The Rust
   `supervisory-entities` crate gains a `WorkflowSnapshot` type
   mirroring the entity-table slice. The TUI's startup path: list
   archived snapshots, hydrate them into state, then call the new
   `read-workflow-events-since` per active workflow to apply the live
   tail. Active (non-archived) workflows replay normally.

**Determinism constraint.** Replaying from a watermark requires
handlers to be safe over the prefix the snapshot already covered.
Today's accumulator uses absolute reassignments (`assoc-in`, `merge`),
not deltas, so this should hold — but every handler needs an
audit before BD-2c lands. Action item: add a property test that
asserts `(replay events) == (replay events-since-N (snapshot-at N))`
for arbitrary N.

**Snapshot format question.** Transit-JSON keeps tooling parity with
events. EDN would be more readable but loses the round-trip guarantees
the consumer relies on. **Recommend Transit-JSON.**

---

## Cross-cutting: sequence numbers in the on-disk shape

Both BD-2b (workflow-grain rotation) and BD-2c (range queries) need the
sequence number to be cheap to read without parsing every file. Today
sequence numbers are inside the Transit-JSON payload. Migrate the
on-disk filename to `{ts}-{seq:08d}-{uuid}.json` so:

- File listing is sortable by sequence with no file open.
- The reader's range API is `O(events-in-window)`, not `O(total-events)`.
- Existing `cleanup-stale-events!` keeps working — mtime is independent.

Backwards compatibility: the reader keeps falling back to the old
filename pattern for events written before the migration. New events
write the new pattern. No retroactive renaming.

---

## Sequencing

```text
BD-2a  ──┐
         ├──▶  BD-2b  ──┐
                        ├──▶  BD-2c  ──▶  BD-3 (consumer-side surfacing)
       (filename change)┘
```

- **BD-2a first.** Without quiesce, retention semantics are undefined —
  you can't rotate or archive a log if you don't know what's actually
  written. Smallest scope, lowest risk.
- **BD-2b next.** Establishes the archive boundary and the workflow
  grain that BD-2c snapshots and the reader's range API both depend on.
  Includes the filename-format migration.
- **BD-2c last.** Snapshot emission upstream; consumer hydration in
  `miniforge-control`. Two paired PRs.
- **BD-3** (retained historical investigation depth) becomes implementable
  once snapshots exist.

## Open questions

1. **Snapshot granularity.** Per workflow, or one global snapshot rolled
   up periodically? Per-workflow keeps producer logic simple and matches
   the natural archival boundary, at the cost of more files. Recommend
   per workflow; revisit if file-count itself becomes the bottleneck.
2. **Snapshot vs replay-from-zero correctness verification.** Property
   test gives high confidence; do we also want a runtime parity check
   (compute table both ways on a sample of starts, log mismatches)? My
   instinct: yes, behind a debug flag. Cheap, catches silent drift.
3. **Cleanup of `archived/` after `MINIFORGE_EVENTS_ARCHIVE_AFTER_DAYS`.**
   Hard delete, or move to a deeper tombstone tier? Hard delete is the
   simpler default; revisit if anyone wants long-term forensic retention.
4. **`drain!` semantics under sink errors.** If a sink's write fails
   during drain, do we retry, or surface the failure and proceed?
   Initial position: surface, log the count of events that failed to
   land, exit non-zero from headless. Don't silently drop.

## Tracking

This RFC corresponds to **BD-2** in
`miniforge-control/work/in-progress/2026-04-25-console-ux-pass-dag.md`.
Each sub-item lands as its own PR(s):

- `BD-2a` — single upstream PR (event-stream `drain!` + `run-workflow!`
  call site + tests).
- `BD-2b` — single upstream PR (archival, scheduled cleanup, filename
  migration). Bigger but contained to the event-stream component.
- `BD-2c` — paired PRs: upstream (snapshot emission, range reader),
  consumer (`WorkflowSnapshot` type, startup hydration). Done after
  BD-2b's filename migration is in.
