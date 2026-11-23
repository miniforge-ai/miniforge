<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Normative Spec Extension: Phase Checkpoint, Machine Snapshot & Resume

**Version:** 0.3.0-draft
**Date:** 2026-11-15
**Status:** Draft
**Conformance:** MUST (on any implementation that persists to disk)

## Purpose

Make miniforge workflows genuinely resumable at **phase granularity** with an
authoritative **execution-machine snapshot**.

N2 §1.1 already lists **resumability** as a core design principle. This
delta defines what that requires of implementations and what the
user-facing contract looks like. Without it, users who are rate-limited,
disconnected, or hit an LLM-provider bug mid-workflow must re-run
already-paid-for phases to make progress — a product failure.

## Why this exists

Real workflows are long. A full SDLC pass through plan → implement →
verify → review → release can take tens of minutes of paid LLM work and
span several provider roundtrips. Three empirical failure modes make
resumability load-bearing, not optional:

1. **Provider slowness** — Anthropic, OpenAI, and others regularly
   throttle or degrade under load. An already-successful plan phase
   should not be paid for twice because the implementer's stream hung.
2. **Network interruptions** — Local process death, laptop sleep, ISP
   hiccups. None of these should cost the user their plan phase output.
3. **Provider bugs** — Model regressions, stream-format drift, MCP
   tool-call collapse, etc. A mid-workflow provider bug should fail
   forward, not fail backward.

Resumability also supports the dogfood iteration loop. When fixing one phase,
engineers should not re-run earlier phases to reach the broken one.

## Conformance Requirements

Requirement IDs are stable identifiers for this delta's normative statements.
They extend N2's families; IDs are never reused.

| ID | Level | Requirement |
|----|-------|-------------|
| N2D.CK.1 | MUST | Write a phase checkpoint and machine snapshot after each successful transition (§1). |
| N2D.CK.2 | MUST | Maintain the workflow manifest atomically after each checkpoint or snapshot write (§2). |
| N2D.CK.3 | MUST | Record `:workflow/spec-hash` at start and compare it on resume (§2, §4). |
| N2D.CK.4 | MUST NOT | Silently resume a workflow whose spec has drifted; error and require the documented override (§4). |
| N2D.CK.5 | MUST | Emit the checkpoint and resume events of N3 §3.21 at the points §9 defines. |
| N2D.CK.6 | MUST | Honor the checkpoint store location and retention rules of §3 and §7. |
| N2D.CK.7 | MUST | Force a write's checkpoint files to disk before publishing the manifest that names them, and hold the run's checkpoint lock while writing (§1). |
| N2D.CK.8 | MUST | Recover a full-stopped run only into a fresh run after confirmed containment/termination, durable fencing, checkpoint validation, and external-effect reconciliation (§11). |

### Test Obligations

1. A crash after phase N resumes at phase N+1 with no re-execution of N.
2. A spec edit between run and resume produces the drift error, not a resume.
3. Every checkpoint write has a corresponding N3 §3.21 event.
4. Full-stop recovery rejects unconfirmed containment, unknown effects,
   unsafe/corrupt checkpoints, stale authority, old capsule identities, and
   attempts to reopen the old run.

---

## Relationship to other specs

- `N2-workflows.md` — parent spec. §1.1 principle #5 mandates
  resumability; this delta operationalizes it.
- `N3-event-stream.md` — checkpoint and snapshot writes emit events
  (`:workflow/checkpoint-written`, `:workflow/checkpoint-write-failed`,
  `:workflow/machine-snapshot-written`,
  `:workflow/machine-snapshot-write-failed`, `:workflow/resumed`).
- `workflow-phase-checkpoint-and-resume.spec.edn` — the work spec that
  implements this extension.
- `agent-stream-watchdog-and-resume.spec.edn` — complementary
  session-level resume (recover an in-flight LLM stream mid-phase).
- `worktree-persistence-scratch-branch.spec.edn` — complementary
  workspace-level resume (recover uncommitted files after process
  death).

The three layers compose. A mid-stream provider hang triggers the watchdog.
Successful session-resume lets the phase complete and checkpoint its result.
After process death, `miniforge resume` restores checkpoints and recreates the
worktree from its scratch ref.

## Spec metadata

- **Title:** Phase Checkpoint, Machine Snapshot & Resume
- **Type:** Normative extension to N2

## Definitions

- **Phase checkpoint** — A durable serialization of a phase's output
  sufficient to reconstruct that phase's contribution to the execution
  context without re-running the phase.
- **Execution-machine snapshot** — Durable authoritative machine state,
  context, and resume metadata. It supports continued execution without
  rebuilding control flow from ad hoc phase indexes.
- **Workflow manifest** — A per-workflow index file describing the
  workflow's identity, spec provenance, completed phases, and resume
  metadata.
- **Checkpoint store** — The filesystem (or equivalent) location where
  phase checkpoints and manifests live.
- **Spec hash** — SHA-256 of the spec file content at workflow start,
  used to detect spec drift between an initial run and a resume.

## Normative Requirements

### §1. Phase checkpoint and machine snapshot after successful transition

When a phase's `:leave` interceptor completes without error, the
workflow runner MUST persist:

1. The phase's contribution to `[:execution/phase-results <phase>]` as
   a single `.edn` file
2. The updated execution-machine snapshot for the workflow run

The write MUST be atomic (write to a temp file in the same directory,
then rename). A partial or corrupt checkpoint MUST NOT be observable by
a later resume.

The files of one write MUST be forced to disk before publishing their
manifest (§2). The manifest is the commit point. An interrupted write
leaves the previous checkpoint whole. The reference implementation first
removes generations not named by the current manifest. It then writes
snapshot and phase files into a new generation under the run directory.
A write MUST hold the run's checkpoint lock, which pinning and checkpoint
GC also take.

If one of these writes fails (disk full, permission denied, etc.), the
workflow MUST NOT fail as a result. The runner MUST log the error and
emit a `:workflow/checkpoint-write-failed` or
`:workflow/machine-snapshot-write-failed` event, then continue. Loss of
a single checkpoint or snapshot degrades resumability for that run but
does not compromise the run itself.

### §2. Workflow manifest

Alongside the phase checkpoints, the runner MUST maintain a manifest
file containing at least:

- `:workflow/id`
- `:workflow/spec-path` — path to the spec file that started the workflow
- `:workflow/spec-hash` — SHA-256 hex of the spec file content at start
- `:workflow/started-at` — ISO-8601 timestamp
- `:workflow/phases-completed` — ordered vector of phase keywords
  checkpointed so far
- `:workflow/machine-state` — current authoritative machine state keyword
- `:workflow/machine-snapshot-path` — path to the latest machine snapshot
- `:workflow/last-checkpoint-at` — ISO-8601 timestamp
- `:workflow/backend` — string identifying the LLM backend in use
  (`claude`, `codex`, `openai`, etc.)
- `:workflow/status` — a member of N2 §2.2's canonical vocabulary
  (`:queued`, `:running`, `:paused`, `:blocked`, `:completed`, `:failed`,
  `:cancelled`)

The manifest MUST be updated atomically after each successful phase
checkpoint or machine snapshot write.

### §3. Checkpoint store location

Implementations MUST default the checkpoint store to
`~/.miniforge/checkpoints/<workflow-id>/`. The location MUST be
configurable via user config (`:checkpoint/root`) and per-invocation
flag. A host-level GC policy MUST NOT delete checkpoints under the
default retention window (see §7).

### §4. Resume from authoritative machine snapshot

Before resuming, implementations MUST reject a full-stopped or terminal
run under N2's terminality contract. Full-stop checkpoint recovery follows §11,
not same-run resume; neither `--force` nor `--from-phase` overrides that fence.

A user-invoked `miniforge resume <workflow-id>` command MUST:

1. Locate the manifest under the checkpoint store for that id
2. Verify the manifest's `:workflow/spec-hash` against the current spec
   file content
3. If the hash matches (or `--force` is passed), reconstruct the
   execution context from the machine snapshot and phase checkpoints:
   - `:execution/machine-snapshot` restored from the persisted snapshot
   - `:execution/machine-state` restored from the authoritative snapshot
   - `:execution/machine-context` restored from the authoritative snapshot
   - `:execution/phase-results` populated from the checkpoint files
   - `:execution/id` set to the original workflow id (no new id, no new
     event-log directory)
   - derived projections such as `:workflow/status` and
     `:workflow/current-phase` recomputed from the machine state
4. Continue execution by dispatching the next legal machine event
5. Emit a `:workflow/resumed` event with
   `{:from-state <machine-state>
     :from-phase <phase-or-nil>
     :skipping <vector-of-skipped-phases>}`

If the hash differs and `--force` is absent, the command MUST report the
drift and list the `--force` override. The command MUST NOT silently resume on drifted
specs.

### §5. Explicit phase override

`miniforge resume <id> --from-phase <phase>` MUST discard checkpoints for
`<phase>` and later phases and truncate `:workflow/phases-completed`.
It MUST rebuild the snapshot to the last state before `<phase>` and resume there. This
supports the case where a bug is discovered in a specific phase but
earlier phases are still valid.

### §6. Resumable workflow enumeration

`miniforge list --resumable` MUST enumerate the checkpoint store and
render one row per workflow with:

- Workflow id
- Start time
- Last completed phase
- Status
- Spec filename or title

Output ordering MUST be most-recent first unless otherwise specified.

### §7. Retention and GC

Implementations MUST retain checkpoints for at least 30 days from
`:workflow/last-checkpoint-at` by default. Pinned workflows (manifest
field `:workflow/pinned? true`) MUST NOT be GC'd automatically. The
retention window MUST be configurable.

GC MUST be rate-limited so it runs at most once per 24 hours of CLI
invocation, not on every command.

### §8. Integration with worktree persistence

When a resume begins, the runner MUST verify the worktree still
exists. If missing, the runner MUST recreate it from the workflow's scratch
branch when available (per `worktree-persistence-scratch-branch`). If neither the worktree nor a scratch
branch is available, the runner MUST report that workspace state is missing.
Further phases cannot run safely without it.

### §9. Event emission

The runner MUST emit the following events (see N3 for the event
stream contract):

- `:workflow/checkpoint-written` after each successful write
  - `:workflow/id`, `:phase/name`, `:checkpoint/path`, `:checkpoint/size-bytes`
- `:workflow/checkpoint-write-failed` on write error
  - `:workflow/id`, `:phase/name`, `:error/message`
- `:workflow/machine-snapshot-written` after each successful snapshot write
  - `:workflow/id`, `:machine/state`, `:snapshot/path`, `:snapshot/size-bytes`
- `:workflow/machine-snapshot-write-failed` on snapshot write error
  - `:workflow/id`, `:machine/state`, `:error/message`
- `:workflow/resumed` on successful resume
  - `:workflow/id`, `:from-state`, `:from-phase`, `:skipping`
- `:workflow/spec-hash-mismatch` on detected drift
  - `:workflow/id`, `:expected-hash`, `:actual-hash`

### §10. Billing and replay semantics

A resumed workflow MUST NOT re-execute phases listed in
`:workflow/phases-completed` or any machine transitions already covered
by the restored snapshot. In particular:

- LLM agents for completed phases MUST NOT be invoked on resume
- Side-effecting operations (commits, PRs, external API calls)
  recorded in a completed phase's checkpoint MUST NOT be re-executed

The goal is straightforward: the user pays for the work they get, not
for the work they lost.

### §11. Recovery after full stop

After N8 §3.5 full stop, checkpoint recovery MUST require explicit operator
authorization and create a new run. Existing N6 provenance MUST link it to the
stopped run and selected checkpoint. The stopped run MUST
remain terminal; its durable fence and effect history MUST NOT be reset.

The selected checkpoint MUST be the latest durable pre-stop checkpoint whose
integrity, provenance, workspace digest, and machine state are verified for
the requested recovery. Latest timestamp alone MUST
NOT establish safety. Suspect capsule output or an uncommitted checkpoint
generation MUST NOT be promoted as a safe recovery point. If no checkpoint
can be shown safe, recovery MUST remain blocked rather than silently replay.

Before any new-run workload effects, the controller MUST confirm N8 §3.5's
durable fence and provider-confirmed containment and termination of the old
runtime. Unresolved credential or egress exposure MUST keep those effects
blocked; reconciling past requests cannot establish safety while the old
runtime can still issue competing effects.

The controller MUST also reconcile durable effect
history and fresh provider observations. This includes requests accepted after
the checkpoint or with unknown outcomes at shutdown. Restoring local state
MUST NOT be treated as undoing external effects or authorizing replay. Unresolved effects MUST
block dependent forward and recovery writes. Any needed compensation MUST be
separately authorized in the new run under N10, never under the stopped run.

The new run MUST acquire a fresh capsule under N11 §4.4 and fresh current
authority. Restoration MUST exclude runtime handles, leases, credentials,
grants, and queued/in-flight commands. Spec drift handling, revalidation,
postconditions, and N3/N6 audit obligations MUST remain in force; a spec-hash
override MUST NOT waive containment, reconciliation, or authority checks.

## Conformance Levels

- **MUST** — §1 (checkpoint and machine snapshot after `:leave`), §2
  (manifest), §4 (resume from authoritative machine snapshot), §9
  (event emission), §10 (no re-execute), §11 (full-stop recovery).
- **SHOULD** — §5 (`--from-phase`), §6 (`list --resumable`), §7 (GC
  policy), §8 (worktree integration).
- **MAY** — Cross-workflow replay (`miniforge replay <old-id> --into
  <spec>`), alternate checkpoint stores (object storage, S3, etc.).

An implementation that satisfies the MUST clauses above conforms to
this extension, even without the SHOULD / MAY features.

## Rationale

### Why phase granularity, not sub-phase

Finer granularity (checkpoint every tool call) is tempting but fragile:
tool invocations often have side effects that make mid-phase resume
unsafe without additional bookkeeping. Sub-phase resume is covered by
the session-level `agent-stream-watchdog-and-resume` spec at a
different layer (the LLM stream, not the workflow state).

### Why spec-hash integrity by default

A user who edits a spec file while a workflow is in flight expects a
different outcome than one who resumes an identical spec. Silently
resuming across a spec edit produces subtly wrong results. The
explicit `--force` flag preserves the override for the cases where the
user knows what they're doing.

### Why machine snapshot, not phase index

Phase indexes alone are not authoritative enough once a workflow can
skip phases, redirect, retry, await supervision, or support multiple
workflow profiles. The machine snapshot carries the actual control-flow
state and eliminates a second hidden state machine in the runner.

### Why same workflow id continues

Using the original workflow id for resumed runs preserves event-log
continuity. All events for a given workflow — whether from the initial
run or a resumed one — live in the same `~/.miniforge/events/<id>/`
directory. Post-mortem readers see the full history.

---

**Version History:**

- 0.3.0-draft (2026-11-15): Operator-approved known-safe checkpoint recovery
  after full stop: new run/runtime/authority and external-effect reconciliation,
  never reopening the stopped run or blindly replaying accepted requests.

- 0.2.0-draft (2026-08-10): Spec-completion pass. Metadata moved from a bulleted
  `## Spec metadata` block to the N1–N15 header form.
  `N2D.CK.*` conformance requirement IDs and test
  obligations added.
