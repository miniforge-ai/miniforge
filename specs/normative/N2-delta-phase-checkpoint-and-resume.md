<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Normative Spec Extension: Phase Checkpoint & Resume

## Purpose

Make miniforge workflows genuinely resumable at **phase granularity**.

N2 §1.1 already lists **resumability** as a core design principle
("Workflows MUST be resumable from last successful phase"). This delta
defines what that requires of implementations and what the user-facing
contract looks like. Without it, users who are rate-limited,
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

Resumability also supports the dogfood iteration loop: when fixing a
bug in one phase, engineers should not re-run earlier phases to get
back to the broken one.

## Relationship to other specs

- `N2-workflows.md` — parent spec. §1.1 principle #5 mandates
  resumability; this delta operationalizes it.
- `N3-event-stream.md` — checkpoint writes emit events
  (`:workflow/checkpoint-written`, `:workflow/checkpoint-write-failed`,
  `:workflow/resumed`).
- `workflow-phase-checkpoint-and-resume.spec.edn` — the work spec that
  implements this extension.
- `agent-stream-watchdog-and-resume.spec.edn` — complementary
  session-level resume (recover an in-flight LLM stream mid-phase).
- `worktree-persistence-scratch-branch.spec.edn` — complementary
  workspace-level resume (recover uncommitted files after process
  death).

The three layers compose: a mid-stream provider hang triggers the
watchdog; if session-resume succeeds, the phase completes; if the phase
completes, its result is checkpointed; if the whole process dies, the
next `miniforge resume` restores from checkpoints and recreates the
worktree from its scratch ref.

## Spec metadata

- **Title:** Phase Checkpoint & Resume
- **Type:** Normative extension to N2
- **Version:** 0.1.0-draft
- **Status:** Draft
- **Conformance:** MUST (on any implementation that persists to disk)
- **Date:** 2026-04-18

## Definitions

- **Phase checkpoint** — A durable serialization of a phase's output
  sufficient to reconstruct that phase's contribution to the execution
  context without re-running the phase.
- **Workflow manifest** — A per-workflow index file describing the
  workflow's identity, spec provenance, completed phases, and resume
  metadata.
- **Checkpoint store** — The filesystem (or equivalent) location where
  phase checkpoints and manifests live.
- **Spec hash** — SHA-256 of the spec file content at workflow start,
  used to detect spec drift between an initial run and a resume.

## Normative Requirements

### §1. Phase checkpoint after successful `:leave`

When a phase's `:leave` interceptor completes without error, the
workflow runner MUST persist the phase's contribution to
`[:execution/phase-results <phase>]` to the checkpoint store as a
single `.edn` file.

The write MUST be atomic (write to a temp file in the same directory,
then rename). A partial or corrupt checkpoint MUST NOT be observable by
a later resume.

If the write fails (disk full, permission denied, etc.), the workflow
MUST NOT fail as a result. The runner MUST log the error and emit a
`:workflow/checkpoint-write-failed` event, then continue. Loss of a
single checkpoint degrades resumability for that run but does not
compromise the run itself.

### §2. Workflow manifest

Alongside the phase checkpoints, the runner MUST maintain a manifest
file containing at least:

- `:workflow/id`
- `:workflow/spec-path` — path to the spec file that started the workflow
- `:workflow/spec-hash` — SHA-256 hex of the spec file content at start
- `:workflow/started-at` — ISO-8601 timestamp
- `:workflow/phases-completed` — ordered vector of phase keywords
  checkpointed so far
- `:workflow/last-checkpoint-at` — ISO-8601 timestamp
- `:workflow/backend` — string identifying the LLM backend in use
  (`claude`, `codex`, `openai`, etc.)
- `:workflow/status` — one of `:running`, `:completed`, `:failed`,
  `:cancelled`, `:paused`

The manifest MUST be updated atomically after each successful phase
checkpoint.

### §3. Checkpoint store location

Implementations MUST default the checkpoint store to
`~/.miniforge/checkpoints/<workflow-id>/`. The location MUST be
configurable via user config (`:checkpoint/root`) and per-invocation
flag. A host-level GC policy MUST NOT delete checkpoints under the
default retention window (see §7).

### §4. Resume from last successful phase

A user-invoked `miniforge resume <workflow-id>` command MUST:

1. Locate the manifest under the checkpoint store for that id
2. Verify the manifest's `:workflow/spec-hash` against the current spec
   file content
3. If the hash matches (or `--force` is passed), reconstruct the
   execution context:
   - `:execution/phase-results` populated from the checkpoint files
   - `:execution/phase-index` pointing to the first phase NOT in
     `:workflow/phases-completed`
   - `:execution/id` set to the original workflow id (no new id, no new
     event-log directory)
4. Continue execution from that phase
5. Emit a `:workflow/resumed` event with
   `{:from-phase <phase> :skipping <vector-of-skipped-phases>}`

If the hash does NOT match and `--force` is NOT passed, the command
MUST error with a message that identifies the drift and lists the
`--force` override. The command MUST NOT silently resume on drifted
specs.

### §5. Explicit phase override

`miniforge resume <id> --from-phase <phase>` MUST discard any
checkpoints for `<phase>` and any later phases, truncate
`:workflow/phases-completed`, and resume from `<phase>`. This supports
the case where a bug is discovered in a specific phase but earlier
phases are still valid.

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
exists. If not, and if a scratch branch exists for the workflow (per
`worktree-persistence-scratch-branch`), the runner MUST recreate the
worktree from that branch. If neither the worktree nor a scratch
branch is available, the runner MUST error with a clear message —
further phases cannot run safely without the workspace state.

### §9. Event emission

The runner MUST emit the following events (see N3 for the event
stream contract):

- `:workflow/checkpoint-written` after each successful write
  - `:workflow/id`, `:phase/name`, `:checkpoint/path`, `:checkpoint/size-bytes`
- `:workflow/checkpoint-write-failed` on write error
  - `:workflow/id`, `:phase/name`, `:error/message`
- `:workflow/resumed` on successful resume
  - `:workflow/id`, `:from-phase`, `:skipping`
- `:workflow/spec-hash-mismatch` on detected drift
  - `:workflow/id`, `:expected-hash`, `:actual-hash`

### §10. Billing and replay semantics

A resumed workflow MUST NOT re-execute phases listed in
`:workflow/phases-completed`. In particular:

- LLM agents for completed phases MUST NOT be invoked on resume
- Side-effecting operations (commits, PRs, external API calls)
  recorded in a completed phase's checkpoint MUST NOT be re-executed

The goal is straightforward: the user pays for the work they get, not
for the work they lost.

## Conformance Levels

- **MUST** — §1 (checkpoint after `:leave`), §2 (manifest), §4 (resume
  from last successful phase), §9 (event emission), §10 (no re-execute).
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

### Why same workflow id continues

Using the original workflow id for resumed runs preserves event-log
continuity. All events for a given workflow — whether from the initial
run or a resumed one — live in the same `~/.miniforge/events/<id>/`
directory. Post-mortem readers see the full history.
