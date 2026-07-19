<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Plan: Configurable PR-label-triggered actions on in-flight workflows

## Problem

When a PR that fixes a bug in the workflow runtime merges to main,
every workflow currently in flight is still executing on the
pre-merge code. The 2026-05-17 dogfood made the cost concrete: a
set of 20 concurrent workflows all paying ~13 hours per verify cycle
on the old slow pre-commit chain, even after the fix landed —
because each workflow's task worktree was created off an older
base.

The first instance we need to solve is **dogfood-fix** rebasing,
but the broader pattern shows up any time we want a merge event
to drive an action on every workflow currently in flight. Bake the general
mechanism now; the dogfood case is its first user.

## Goal

A label on a merged PR triggers a configured action on every
in-flight workflow. Concretely, for `dogfood-fix`:

1. PR labeled `dogfood-fix` merges to main.
2. The **pr-label-watcher** (pure-Clojure, zero-token) observes
   the merge event, matches the label against the registry, joins
   with supervisory-state to find active workflows whose base SHA
   is NOT an ancestor of the merge SHA (`git merge-base
   --is-ancestor base-sha merge-sha` returns non-zero), and emits a
   match payload.
3. On match, the **meta-agent** is invoked (LLM, judgement only)
   with `{:pr/number :pr/labels :pr/merge-sha :action
   :matched-workflows [...]}`. It decides whether to inject (default
   yes), staggers across matched workflows to avoid thundering herd, and
   emits `:workflow/inject {:workflow-id :action ...}` per approval.
4. At the next safe seam (phase boundary), the **workflow runner**
   pauses, runs `rebase-onto! origin/main` against the task worktree,
   and resumes — picking up the fix for the remainder of the run.
5. If rebase conflicts, the workflow is parked in
   `:attention-required` instead of silently losing work.

## Non-goals

- Changing CI semantics. CI continues to gate PR merges; this plan
  is about what happens AFTER merge for already-running runs.
- Re-running already-completed phases. Rebase happens at the
  boundary; previously-completed phases keep their checkpoint
  outputs.
- A general "workflow restart on any push." This is label-gated
  and only the named watcher → meta-agent → runner path acts on it.

## Architecture

### Components touched (single owner per responsibility)

| Responsibility | Owner |
|---|---|
| PR merge-event emission | `components/pr-lifecycle` (`:pr/labeled-pr-merged` event) |
| Mechanical label → action match | new brick `components/pr-label-watcher` (no LLM, no tokens) |
| Workflow base-SHA registry | `components/supervisory-state` (new sub-key under `[:execution/environment-metadata :base-sha]`) |
| Judgement / multi-workflow staggering | meta-agent under `components/agent/src/.../specialized/` (invoked only on watcher hit) |
| Pause-rebase-resume actuator | `components/workflow` (new hook honored at the existing `await-resume!` seam in `runner/execute-single-iteration`) |
| Rebase mechanics | `components/dag-executor` — NEW `rebase-onto!` sibling of `workspace/git-restore!` (NOT `restore-workspace!`, which is the workspace-bundle-restore protocol method, not a rebase) |
| Config | new resource `resources/config/pr-label-actions.edn` |

Earlier drafts of this plan used overlapping names ("meta-coordinator",
"observer-tier coordinator") for the watcher and the meta-agent. The
table above is the canonical assignment — each responsibility has
exactly one owner.

### Data flow (one round trip)

```text
PR #N (label: dogfood-fix) merges to main
       │
       ▼
pr-lifecycle emits :pr/labeled-pr-merged {:pr/number :pr/labels :pr/merge-sha}
       │
       ▼
pr-label-watcher (PURE Clojure, no LLM)
   - look up label (string) → action in pr-label-actions.edn
   - "dogfood-fix" → :rebase-and-resume
   - join with supervisory-state to find matching workflows
     (filter where workflow.base-sha is NOT an ancestor of merge-sha
      — checked via `git merge-base --is-ancestor base merge`; git
      SHAs are not orderable so ancestry is the only correct test)
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
       b. dag-exec/rebase-onto! origin/main into the task worktree
          (NEW fn sibling of workspace/git-restore!; runs
           git fetch origin && git rebase origin/main, NOT a
           workspace-bundle restore)
       c. if conflict → transition to :attention-required;
          emit :workflow/rebase-conflict event with diff hints
       d. else → :resume transition; continue pipeline
```

### Event and config shapes

`:pr/labeled-pr-merged` event (canonical, all namespaced):

```clojure
{:pr/number    integer
 :pr/labels    #{"dogfood-fix" "..."}   ; string labels — GitHub-native
 :pr/merge-sha "abc123..."}
```

Watcher match payload (passed to meta-agent on hit):

```clojure
{:pr/number          integer
 :pr/labels          #{...}
 :pr/merge-sha       "abc123..."
 :action             :rebase-and-resume
 :matched-workflows  [{:workflow/id ... :base-sha ...} ...]}
```

`resources/config/pr-label-actions.edn`:

```clojure
{:pr-label-actions/registry
 ;; Registry keys are STRINGS to match GitHub's native label shape
 ;; (case-sensitive, no whitespace normalization — labels in GitHub
 ;; are normalized at creation time by the project; this lookup is
 ;; a literal compare).
 {"dogfood-fix"
  {:action      :rebase-and-resume
   :description "Pause, rebase onto post-merge main, resume"
   :on-conflict :attention-required
   ;; Match predicate: workflow's base SHA must NOT be an ancestor
   ;; of the merge SHA — i.e. the workflow predates the fix and
   ;; needs to pick it up. Git SHAs are not orderable; ancestry is
   ;; resolved via `git merge-base --is-ancestor base merge`
   ;; (exit 0 → ancestor → skip; exit 1 → not ancestor → match).
   :applies-when {:workflow.base-sha/not-ancestor-of-merge true}}

  ;; Future entries — no code edits required, only registry data.
  ;; e.g. "hot-config-reload" → reload runtime config from disk
  ;;      "budget-pause" → pause workflows until human reviews quota
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
   should this action be staggered across N matched workflows to avoid
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

### M1 — Label config + merge event + base-sha

- New resource `resources/config/pr-label-actions.edn` with the
  `"dogfood-fix" → :rebase-and-resume` entry (string-keyed registry).
- `components/pr-lifecycle` emits `:pr/labeled-pr-merged` on every
  merge whose label set intersects the configured registry.
- Record the workflow's base SHA at `acquire-environment!` time
  under `[:execution/environment-metadata :base-sha]`. That parent
  key is already in `persisted-execution-keys`, so adding the
  sub-key needs no schema-key addition — just populate it.
- Backfill: for active workflows whose checkpoint predates this
  change, the watcher's match payload omits them (no base-sha →
  unknown ancestry → skip); the operator can manually trigger
  rebase via the existing pause/resume controls.

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

- Add `rebase-onto!` to `components/dag-executor/src/.../workspace.clj`
  as a sibling of the existing `git-restore!`. NEW operation —
  distinct from the `restore-workspace!` protocol method (which
  bundle-restores or fetch+checkouts a workspace, not a rebase).
- `rebase-onto!` runs:
  1. `git -C <worktree> fetch origin main`
  2. `git -C <worktree> rebase origin/main`
- On clean rebase: emit `:workflow/rebased` event, resume FSM.
- On conflict: capture the conflict-file list, emit
  `:workflow/rebase-conflict`, transition workflow to
  `:attention-required`, notify via existing intervention dashboard.
  No silent loss (per `feedback_runtime_state_never_git_tracked`).
- Expose via `components/dag-executor/interface.clj` so the
  workflow runner's actuator can call it through the same surface
  it uses for `restore-workspace!`.

### M4 — Multi-workflow test

- Spin up N≥3 long-running workflows (the dogfood spec is fine).
- Land a no-op PR labeled `dogfood-fix`.
- Assert all N workflows pick up the rebase within one phase
  boundary, no work lost, no rogue commits, conflict-injected
  fourth workflow correctly lands in `:attention-required`.

## Open questions

1. **Placement of the new `pr-label-watcher` brick** — sit next to
   `components/pr-lifecycle` (where the merge events come from) or
   under `components/orchestrator` (where cross-workflow concerns
   live today)? Leaning toward a sibling of `pr-lifecycle` so the
   event-source and the matcher stay close, but defer until M2.

2. ~~**GitHub event delivery**~~ — **resolved.** OSS ships
   **ETag-cached polling** of `/repos/:owner/:repo/events` on a
   30–60 s interval as the default transport. 304s are near-zero
   against the rate limit; each user has their own 5000/hour PAT
   bucket so 100 users behind one ASN don't collide (GitHub
   rate-limits per-token, not per-IP).

   Pluggable behind a `:pr-events/source` protocol — a deployment
   with a public inbound endpoint (webhook receiver, SSE relay,
   etc.) can swap in a sub-second transport without touching the
   watcher or the meta-agent. Those alternate transports are NOT
   part of this OSS plan; downstream products that want them ship
   their own `:pr-events/source` implementation in their own repo.

   Latency budget for the OSS default:

   | Use case | Latency needed | ETag-poll fits? |
   |---|---|---|
   | `dogfood-fix` rebase | minutes (next phase boundary anyway) | ✓ |
   | Hypothetical `incident-pause` | seconds | ✗ — needs a push transport |

3. **Phase-boundary granularity for rebase** — current seam is
   "between phases." Inside a DAG sub-workflow's parallel fan-out,
   "between phases" is more nuanced. Defer DAG-aware rebase to a
   follow-up; M3 ships the linear-pipeline path.

4. **Per-workflow opt-out** — should some workflows be exempt from
   `dogfood-fix` rebase (e.g. running an experiment that depends on
   a specific pre-fix state)? Probably yes via a workflow-spec
   `:rebase-eligible? false` flag. Default true.

5. ~~**What's the source of truth for base SHA**~~ — **resolved.**
   Record it at `acquire-environment!` time under
   `[:execution/environment-metadata :base-sha]`. That parent key
   is already persisted, so no new top-level schema key. Active
   pre-change workflows have no base-sha → the watcher omits them
   from the match payload (silent skip, no error). Operator can
   trigger rebase manually via the existing pause/resume controls
   if they need to catch a pre-change workflow up.

## Risks

- **Mid-phase rebase race.** A poll at phase boundary should be
  safe, but the runner's `(execute-pipeline-loop)` is the source of
  truth — confirm the poll seam can't fire mid-phase or mid-write.
- **Conflict noise.** If dogfood-fix PRs frequently touch files
  the running agents are also touching (`agents.md`, prompts,
  `bb.edn`), the conflict-injection rate may be high. Mitigation:
  the conflict path is the existing `:attention-required` flow, not
  silent loss.
- **Multi-workflow thundering herd.** N workflows all rebasing at
  once spike GitHub API + local git. Mitigation: the meta-agent
  serializes injections with a configurable `:rebase-concurrency`
  (default 4).
- **Label drift.** A label added to a PR after merge wouldn't
  trigger because the event already fired. Out-of-scope; document
  as "label-before-merge" hygiene.

## What this plan does NOT replace

- Rerunning failed workflows from scratch after a fix is still the
  correct path when the workflow has lost too much state to safely
  rebase.
- Manual worktree-patch escape hatch (this session's M0 maneuver)
  remains a documented hot-fix for the unhappy path.
