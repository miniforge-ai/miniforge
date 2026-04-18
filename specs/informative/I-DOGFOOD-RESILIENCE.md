<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# I-DOGFOOD-RESILIENCE — Informative

**Theme id:** `:dogfood-resilience` (see `work/themes.edn`)
**Status:** In flight
**Anchor specs:** N2 §1.1 #5 (resumability), N2-delta (checkpoint-and-resume), N3 §3.17

## Why this theme exists

Miniforge is designed to run miniforge — that's the whole pitch. In
practice, during any 1-hour window of real operation we expect at least
one of the following to happen:

- Anthropic or OpenAI rate-limits us (observed, repeatedly)
- A provider's stream hangs silently for minutes (observed, 2026-04-17)
- Our process tree gets restarted for unrelated reasons (observed)
- A provider ships a regression in tool-use or stream formatting
- The local network blips
- A laptop lid closes

Each of these, today, costs us **the full run's completed work**.
A workflow that spent 5 minutes planning, 10 implementing, and 8
verifying — 23 minutes of paid LLM time — has to redo all of it when
the 24th-minute release phase can't commit because pre-commit
`poly check` failed. That is not a correct product.

Resilience-to-interruption has to be a first-class property. If a
user pays for a plan, they keep the plan. If a phase emits artifacts,
those artifacts survive process death. If a provider goes slow, we
detect it and recover, not sit on a stuck HTTP stream for 13 minutes.

This theme is the set of work specs that, together, make that true.

## Scope

### In scope

1. **Phase-granularity checkpointing** — every completed phase writes
   a durable checkpoint with enough state to resume without re-running.
2. **Resume semantics** — `miniforge resume <workflow-id>` restores
   from checkpoints and continues from the next un-checkpointed phase.
   Same workflow id, same event log, no token re-spend.
3. **Spec-hash integrity** — resume detects spec edits between the
   initial run and the resume, refuses by default, provides a
   documented `--force` override.
4. **Stream-stall watchdog** — per-phase event-emission watchdog kills
   a hung agent after a configurable gap (default 90s) and retries via
   the backend's session-resume mechanism.
5. **Backend failover** — if session-resume also stalls on the same
   backend, escalate to `self-healing/backend-auto-switch` per user
   config.
6. **Worktree persistence + scratch refs** — implementer file writes
   are mirrored as commits to `refs/miniforge/scratch/<workflow-id>`
   so a process crash never costs uncommitted code.
7. **Structured tool-call events** — agents emit
   `:agent/tool-call-started` + `:tool/call-completed` with tool name,
   args digest, result digest, duration. Replaces the current opaque
   `:agent/status :tool-calling` pings.
8. **Workflow dependency declarations** — specs can declare
   `:spec/depends-on` so the supervisor won't schedule B before A has
   landed. Prevents the gates-vs-remediation race we observed.

### Out of scope

- New provider backends (covered by `backend-failover.spec.edn`
  separately)
- Checkpoint storage backends beyond local filesystem — cloud storage
  is a follow-up once the local-FS implementation proves out
- UI / TUI integration — `miniforge list --resumable` is CLI only for
  the first pass; TUI follows
- Retry policies for specific LLM-level failures (malformed output,
  parse errors) — covered by `plan-from-agent-dag-wiring` + the
  planner prompt improvements in PR #575

## Narrative: what a resilient workflow looks like

### Scenario A — rate limit mid-implement

1. Agent is partway through implement phase. Anthropic returns 429.
2. Watchdog detects event-emission gap > 90s.
3. Runtime kills the agent subprocess, emits
   `:phase/termination-reason :agent-stalled`.
4. `self-healing/backend-auto-switch` evaluates: same backend, this
   is attempt 1 → retry via session-resume.
5. Claude Code restarts with `--resume <session-id>`. The session
   context + prior tool-calls come back.
6. Agent completes its work. Implement phase checkpoints. Verify +
   review + release run normally.

Cost: one ~90s watchdog kill + the retry's own LLM time. No re-plan.

### Scenario B — process dies mid-verify

1. Process tree dies (laptop lid closes, OS update, whatever).
2. User returns, runs `miniforge resume <workflow-id>`.
3. Runtime reads the checkpoint directory. `:workflow/phases-completed`
   is `[:explore :plan :implement]`. Verify's checkpoint is absent.
4. Spec-hash check passes (user didn't edit the spec).
5. Runtime restores `:execution/phase-results` from the three
   checkpoints. Worktree is recreated from the scratch ref if the
   original is gone.
6. Workflow continues from verify. No re-plan, no re-implement.

Cost: zero re-run tokens. Only the phases that didn't finish run again.

### Scenario C — provider narrative-only regression

1. Planner invokes model. Model emits reasoning text but never calls
   the artifact-submit MCP tool and never outputs EDN.
2. `parse-plan-response` returns nil. Runtime throws
   `:anomalies.agent/invoke-failed`.
3. Phase outcome propagates as `:failure` (PR #574 fix).
4. Next layer: policy-pack compiled rule checks if this is a known
   narrative-only failure mode. Runtime retries with a constrained
   "emit plan only, no prose" prompt.
5. Second attempt succeeds. Plan phase completes, checkpoints,
   continues.

Cost: one extra planner turn. No full-spec re-run.

## Work specs in this theme

The theme catalog maps to concrete work specs. All carry
`:spec/priority :theme :dogfood-resilience`:

| Work spec | Tier | Role |
|---|---|---|
| `plan-from-agent-dag-wiring.spec.edn` | `:blocker` | Unlocks DAG decomposition — every non-trivial spec becomes parallel children, cutting per-retry cost |
| `workflow-phase-checkpoint-and-resume.spec.edn` | `:blocker` | The checkpoint + resume core. Biggest token conservation win |
| `event-log-tool-visibility.spec.edn` | `:blocker` | The visibility substrate the watchdog depends on |
| `agent-stream-watchdog-and-resume.spec.edn` | `:blocker` | Detect + recover from stalled streams |
| `worktree-persistence-scratch-branch.spec.edn` | `:blocker` | Non-lossy worktree semantics |
| `workflow-dependency-declarations.spec.edn` | `:high` | Cross-spec ordering so concurrent runs don't race |

## Completion criteria

The theme is `:completed` when:

1. A spec killed mid-implement resumes cleanly with no plan re-run
2. A 90-second provider stall triggers the watchdog and recovers via
   session-resume without dropping the workflow
3. `~/.miniforge/events/<wf-id>/` contains tool-call events with
   `:tool/name` and `:tool/duration-ms` for every call at default
   verbosity
4. The event log is sufficient to reconstruct what the agent tried,
   what it succeeded at, and where it stalled — no need to scrape
   live stdout
5. A spec declaring `:spec/depends-on` stays blocked until its deps
   have `:spec.status :merged-to-main`

## Non-goals

Worth restating: **this theme does not** add capacity (new backends),
change the agent prompting strategy (separate theme), or replace the
canonical SDLC phases. It makes the existing SDLC survive failure
modes it currently doesn't.

## Cross-theme relationships

- **`:context-quality`** — complementary. Better context = fewer
  retries = less need for resilience. Both compound.
- **`:dag-orchestration`** — blocking relationship. Without DAG
  decomposition, checkpoints are phase-coarse (can resume plan /
  implement / verify individually). With DAG decomposition,
  checkpoints are task-coarse inside implement. Land DAG first OR
  accept that resilience is phase-level only in v1.
- **`:polylith-compliance`** — orthogonal but both block release
  phase success rate. Dogfood resilience only matters if the workflow
  can finish; polylith compliance ensures it can.

## Open questions

- **Checkpoint storage default** — start local FS under
  `~/.miniforge/checkpoints/`. Cloud-storage backend is a follow-up.
- **GC policy** — 30 days for unpinned, indefinite for pinned. Should
  GC run on `miniforge *` CLI startup (rate-limited) or as a separate
  cron? Default to startup-rate-limited; add cron as follow-up if
  needed.
- **Should resume be default?** — when an abandoned workflow exists
  and the user re-invokes the same spec, offer resume? Or always
  start fresh? Default fresh for safety in v1, opt-in via
  `miniforge resume <id>` explicitly.

## References

- `work/themes.edn` — theme catalog this document anchors
- `work/workflow-phase-checkpoint-and-resume.spec.edn`
- `work/agent-stream-watchdog-and-resume.spec.edn`
- `work/worktree-persistence-scratch-branch.spec.edn`
- `work/event-log-tool-visibility.spec.edn`
- `work/workflow-dependency-declarations.spec.edn`
- `work/plan-from-agent-dag-wiring.spec.edn`
- `specs/normative/N2-delta-phase-checkpoint-and-resume.md` — the
  MUST-level requirements the work specs implement
- `specs/normative/N2-workflows.md` §1.1 #5 — parent mandate for
  resumability
