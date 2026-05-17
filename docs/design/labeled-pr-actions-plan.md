# Plan: Configurable PR-label-triggered actions on in-flight workflows

## Problem

When a PR that fixes a bug in the workflow runtime merges to main,
every workflow currently in flight is still executing on the
pre-merge code. The 2026-05-17 dogfood made the cost concrete: a
fleet of 20 running workflows all paying ~13 hours per verify cycle
on the old slow pre-commit chain, even after the fix landed —
because each workflow's task worktree was created off an older
base.

The first instance we need to solve is **dogfood-fix** rebasing,
but the broader pattern shows up any time we want a merge event
to drive an action on running fleet members. Bake the general
mechanism now; the dogfood case is its first user.

## Goal

A label on a merged PR triggers a configured action on every
in-flight workflow. Concretely, for `dogfood-fix`:

1. PR labeled `dogfood-fix` merges to main.
2. The meta-coordinator (one of the existing meta-agents, not the
   workflow runner) observes the merge event.
3. For every active workflow whose base SHA is older than the merge
   SHA, the meta-coordinator queues a `:rebase-and-resume` injection.
4. At the next safe seam (phase boundary), the workflow runner
   pauses, fetches origin/main, rebases its task worktree, and
   resumes — picking up the fix for the remainder of the run.
5. If rebase conflicts, the workflow is parked in
   `:attention-required` instead of silently losing work.

## Non-goals

- Changing CI semantics. CI continues to gate PR merges; this plan
  is about what happens AFTER merge for already-running runs.
- Re-running already-completed phases. Rebase happens at the
  boundary; previously-completed phases keep their checkpoint
  outputs.
- A general "workflow restart on any push." This is label-gated
  and meta-coordinator-driven, not push-driven.

## Architecture

### Components touched

- **`components/pr-lifecycle`** — already watches PR state changes.
  Emit a new event when a PR with a labeled set merges. New event
  shape: `:pr/labeled-pr-merged {:pr/number :pr/labels :pr/merge-sha}`.
- **`components/supervisory-state`** — owns the active-workflow
  registry. Add `:workflow/base-sha` to the snapshot keys so the
  coordinator can compare against the merge SHA.
- **`components/observer`** (or a sibling under `components/meta-*`)
  — new responsibility: subscribe to `:pr/labeled-pr-merged`, match
  label against the action registry, fan out injection requests to
  the matching workflows. This is the **meta-coordinator** layer.
- **`components/workflow`** — expose a `pause-rebase-resume!`
  control-plane hook that the meta-coordinator can invoke. The
  hook is honored at the next phase boundary (same seam as
  `await-resume!` / `check-stopped!` in `runner/execute-single-iteration`).
- **`components/dag-executor`** — `restore-workspace!` already
  exists; the new rebase action calls it after the fetch.
- **Config** — new resource `resources/config/pr-label-actions.edn`
  shipping with one entry (`dogfood-fix`) plus the action registry
  shape.

### Data flow (one round trip)

```text
PR #N (label: dogfood-fix) merges to main
       │
       ▼
pr-lifecycle emits :pr/labeled-pr-merged {pr-number labels merge-sha}
       │
       ▼
pr-label-watcher (PURE Clojure, no LLM)
   - look up label → action in pr-label-actions.edn
   - dogfood-fix → :rebase-and-resume
   - join with supervisory-state to find matching workflows
     (filter where workflow.base-sha < merge-sha)
   - emit a single match payload OR nothing
       │            ↓ (nothing → silent, zero tokens)
       │
       ▼   (on match)
meta-agent (LLM, invoked only on hit)
   - sanity-check the recommendation (mid-release? quota guard?)
   - decide rebase order / staggering across N matched workflows
   - emit :workflow/inject {:workflow-id :action ...} per approval
       │
       ▼
workflow runner — at next phase boundary (await-resume! seam):
   - poll inject queue
   - if :rebase-and-resume:
       a. pause the FSM (existing :pause transition)
       b. dag-exec/restore-workspace! with branch=main
          (rebase semantics — fast-forward where possible,
           merge-conflict-aware where not)
       c. if conflict → transition to :attention-required;
          emit :workflow/rebase-conflict event with diff hints
       d. else → :resume transition; continue pipeline
```

### Configuration shape (proposed)

```clojure
{:pr-label-actions/registry
 {:dogfood-fix {:action :rebase-and-resume
                :description "Pause, rebase onto post-merge main, resume"
                :on-conflict :attention-required
                :applies-when {:workflow/base-sha :older-than-merge}}
  ;; Future: room for other label-action pairs without code edits.
  ;; e.g. :hot-config-reload → reload runtime config from disk
  ;;      :budget-pause → pause workflows until human reviews quota
  }}
```

### Why a mechanical watcher + meta-agent on hit, not an LLM loop

Two distinct responsibilities that should NOT both live in an
LLM-driven agent:

1. **The watcher** — a pure-Clojure loop that polls / receives
   PR-merge events, matches the labels against the EDN registry,
   and emits a structured `{:label :action :pr-number :merge-sha
   :matched-workflows [...]}` payload on hit. This is mechanical:
   no LLM, no tokens, no judgement. It runs all the time and stays
   silent until a configured label fires. Lives in
   `components/pr-lifecycle` (or a sibling brick) next to the
   existing merge-event listener.

2. **The meta-agent** — invoked ONLY when the watcher produces a
   match, with the payload + the configured action recommendation.
   The agent's job is the parts that need judgement: does this
   workflow really benefit from the rebase right now (maybe it's
   one phase from release), can the conflict be auto-resolved,
   should this fleet-wide action be staggered to avoid
   thundering-herd. Token cost scales with merge events, not with
   uptime.

And neither lives in the workflow runner itself. Putting label-watch
inside the runner would (a) couple every workflow to GitHub's webhook
surface, (b) duplicate poll loops across N running workflows, and
(c) violate the "runner runs phases" charter. The runner only
exposes the control-plane hook the meta-agent ultimately calls; the
when/why lives upstream.

## Milestones

### M0 — Manual repro and shape verification (this session)

- Manually patch the in-flight dogfood worktree with the smoke set
  (done in this session — confirmed verify path picks up the new
  `bb.edn` for the next test invocation).
- Verifies the technical premise: an in-flight worktree CAN be
  patched and verify rolls into the new chain.

### M1 — Label config + merge event

- New resource `resources/config/pr-label-actions.edn` with the
  `dogfood-fix → :rebase-and-resume` entry.
- `components/pr-lifecycle` emits `:pr/labeled-pr-merged` on every
  merge whose label set intersects the configured registry.
- Add `:workflow/base-sha` to `persisted-execution-keys` so the
  coordinator can compare. Backfill via a one-time scan that reads
  HEAD from each active checkpoint at startup.

### M2 — Mechanical watcher → match payload

- New pure-Clojure brick (e.g. `components/pr-label-watcher`) that:
  - Subscribes to `:pr/labeled-pr-merged` from M1.
  - Loads `pr-label-actions.edn` registry.
  - Joins against supervisory-state's active workflows + their
    `:workflow/base-sha`.
  - Emits `{:label :action :pr-number :merge-sha :matched-workflows
     [...]}` on hit; emits nothing on miss.
- Zero LLM, zero tokens. Pure deterministic match.

### M2b — Meta-agent on hit + injection

- The meta-agent (one of the existing meta-* agents under
  `components/agent/src/.../specialized/`) subscribes to the
  watcher's match output. Only invoked when a payload arrives.
- Decides whether to inject (default yes; agent can decline if
  the workflow is e.g. mid-release).
- For approved injections: emits `:workflow/inject` commands.
- The workflow runner's `await-resume!` seam grows a
  `(check-pending-injections! ...)` poll. Initially the only
  injection action is `:rebase-and-resume`.

### M3 — Rebase + resume actuator

- `components/dag-executor/workspace/restore-workspace!` already
  handles the bundle-restore path. Add a `rebase-onto!` sibling
  that runs `git fetch origin main && git rebase origin/main`
  inside the task worktree.
- On clean rebase: emit `:workflow/rebased` event, resume FSM.
- On conflict: emit `:workflow/rebase-conflict` event with
  conflict-file list, transition workflow to `:attention-required`,
  notify via existing intervention dashboard.

### M4 — Fleet test

- Spin up N≥3 long-running workflows (the dogfood spec is fine).
- Land a no-op PR labeled `dogfood-fix`.
- Assert all N workflows pick up the rebase within one phase
  boundary, no work lost, no rogue commits, conflict-injected
  fourth workflow correctly lands in `:attention-required`.

## Open questions

1. **Placement of the meta-coordinator brick** — does this live
   under `components/observer`, `components/orchestrator`, or a
   new `components/fleet-coordinator`? The existing observer brick
   already subscribes to event-stream, but its current scope is
   per-workflow not cross-workflow.

2. ~~**GitHub event delivery**~~ — **resolved.** Two transports
   behind a single `:pr-events/source` protocol:

   - **Fleet** (hosted control-plane with a public endpoint) uses
     **GitHub webhooks** subscribed to PR `closed` events with
     `merged: true`. Sub-second latency, trivial cost, GitHub-App
     auth.
   - **OSS** (no public inbound) uses **ETag-cached polling** of
     `/repos/:owner/:repo/events` on a 30–60 s interval. 304s are
     near-zero against the rate limit; each user has their own
     5000/hour PAT bucket so 100 OSS users behind one ASN don't
     collide (GitHub rate-limits per-token, not per-IP).

   SSE-via-Fleet-relay was considered and deferred. It would mean
   Fleet's webhook receiver fans out merge events to subscribed OSS
   clients over an open HTTP stream — workable, but it makes Fleet
   a hard dependency for OSS notification and the latency win
   (~30 s) doesn't justify it for `dogfood-fix`. Revisit only if a
   future label-action genuinely needs sub-second on OSS.

   Latency budget table:

   | Use case | Latency needed | Webhook (Fleet) | ETag-poll (OSS) |
   |---|---|---|---|
   | `dogfood-fix` rebase | minutes (next phase boundary anyway) | ✓ | ✓ |
   | Hypothetical `incident-pause` | seconds | ✓ | ✗ |

3. **Phase-boundary granularity for rebase** — current seam is
   "between phases." Inside a DAG sub-workflow's parallel fan-out,
   "between phases" is more nuanced. Defer DAG-aware rebase to a
   follow-up; M3 ships the linear-pipeline path.

4. **Per-workflow opt-out** — should some workflows be exempt from
   `dogfood-fix` rebase (e.g. running an experiment that depends on
   a specific pre-fix state)? Probably yes via a workflow-spec
   `:rebase-eligible? false` flag. Default true.

5. **What's the source of truth for `:workflow/base-sha`** — the
   workflow's first `acquire-environment!` records the branch HEAD
   but the snapshot today doesn't store the SHA. Add to
   `persisted-execution-keys` in M1; backfill question is whether
   any active workflows need their SHA reconstructed from git
   history vs lost-and-reset.

## Risks

- **Mid-phase rebase race.** A poll at phase boundary should be
  safe, but the runner's `(execute-pipeline-loop)` is the source of
  truth — confirm the poll seam can't fire mid-phase or mid-write.
- **Conflict noise.** If dogfood-fix PRs frequently touch files
  the running agents are also touching (`agents.md`, prompts,
  `bb.edn`), the conflict-injection rate may be high. Mitigation:
  the conflict path is the existing `:attention-required` flow, not
  silent loss.
- **Fleet thundering herd.** N workflows all rebasing at once
  spike GitHub API + local git. Mitigation: meta-coordinator
  serializes the injections with a configurable
  `:rebase-concurrency` (default 4).
- **Label drift.** A label added to a PR after merge wouldn't
  trigger because the event already fired. Out-of-scope; document
  as "label-before-merge" hygiene.

## What this plan does NOT replace

- Rerunning failed workflows from scratch after a fix is still the
  correct path when the workflow has lost too much state to safely
  rebase.
- Manual worktree-patch escape hatch (this session's M0 maneuver)
  remains a documented hot-fix for the unhappy path.
