<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Five specs from today's dogfood post-mortem

## Context

Two miniforge runs earlier today (polylith-compliance-remediation +
polylith-workflow-gates-and-scope) both ended in failure. Investigation of
the event logs at `~/.miniforge/events/<workflow-id>/*.json` and a code
trace of the DAG executor surfaced five distinct problems and one root
cause. This PR captures them as specs.

The root cause was NOT "spec too broad." Miniforge is explicitly designed
to decompose specs into a DAG of smaller parallel workflows — the DAG
executor exists and is wired. The decomposition just isn't firing for
LLM-generated plans.

## What landed

Five spec files under `work/`. All are `:workflow-type :canonical-sdlc`
and written at a scope small enough that a single miniforge workflow
should be able to complete each without hitting the failure modes they
themselves describe.

### 1. `plan-from-agent-dag-wiring.spec.edn` — **root-cause fix**

The biggest single finding. `components/dag-executor/` and
`workflow/dag_orchestrator.clj` are fully built; `execution.clj:344-350`
wires `dag-applicable?` to fire when the plan phase output contains
`:plan/id`. The `plan-from-spec-tasks` fast path (specs with
pre-declared tasks) emits `:plan/id` correctly. The `plan-from-agent`
LLM path (the common case) does NOT. So for any spec without
pre-declared tasks, the DAG orchestrator never activates and the
implementer sees the entire spec in one monolithic phase. That is what
caused the 13-minute hang.

Fix scope: `components/phase-software-factory/.../plan.clj` lines 86+
wrap the LLM planner output with `:plan/id` + validate against a malli
schema so invalid DAGs fail fast instead of silently falling back.

### 2. `agent-stream-watchdog-and-resume.spec.edn`

The direct cause of today's 11-minute silent stall: Anthropic was
rate-limiting / slow, the agent's HTTP stream stuck open with no events,
miniforge had no detector. The fix: per-phase event-gap watchdog that
kills the agent when the inter-event gap exceeds ~90s, resumes via the
backend's session-resume mechanism (Claude Code `--resume`, Codex
equivalent), and falls over to `self-healing/backend-auto-switch` if
resume also hangs. Emits `:phase/termination-reason :agent-stalled` so
hangs are visible in the event log instead of mis-attributed to "no
files written."

### 3. `event-log-tool-visibility.spec.edn`

Today the event log had 203 `:agent/status :tool-calling` events for
the remediation run. None carried the tool name, args, or result. We
could count tool calls but couldn't diagnose what the agent was doing.
Expand the schema to `:agent/tool-call-started` + `:tool/call-completed`
with digested args/results, add phase heartbeat events every 30s, and
a `miniforge events show <workflow-id>` post-mortem CLI that renders a
human-readable timeline including gaps.

### 4. `worktree-persistence-scratch-branch.spec.edn`

The gates workflow produced real implementation (verify + review both
passed 3/3 gates), but the worktree lived in `/tmp/mf-wt-gates` and got
GC'd on process restart — 8 minutes of verified work permanently lost.
Two fixes: move worktrees out of `/tmp` (default to
`~/.miniforge/worktrees/`), and commit every implementer file write to
`refs/miniforge/scratch/<workflow-id>` in the parent repo. Plus a
`miniforge recover <workflow-id>` CLI to pull uncommitted work back
from the scratch ref after a crash.

### 5. `workflow-dependency-declarations.spec.edn`

Gates ran in parallel with remediation. Gates' release phase ran
`poly check` via pre-commit, which reported the 13 structural errors
remediation was mid-way through fixing, and gates aborted. No mechanism
exists today for "spec B depends on spec A landing." Add
`:spec/depends-on` with requirement types (`:succeeded`,
`:merged-to-main`, `:structural-clean`) and have the supervisor refuse
to schedule a dependent spec until its deps resolve. Same schema works
for intra-DAG dependencies from spec #1's decomposition.

## Proposed landing order

1. **#1 (DAG wiring)** — unlocks decomposition. Any retry after this
   benefits from the architecture miniforge already has.
2. **#3 (event visibility)** — provides the observability #2 needs to
   make decisions.
3. **#2 (watchdog + resume)** — builds on #3's events.
4. **#4 (scratch branch)** — independent; land anytime.
5. **#5 (dep declarations)** — after #1 lands; uses the same supervisor
   machinery as the decomposed DAG.

## Related

- Depends on merge of #568 (supervisory-state component + dogfood
  diagnosis write-up) for historical context, but this PR does not
  require #568 for functional reasons.
- `specs/normative/N2-workflows.md §13` already documents DAG-based
  multi-task execution as intended behavior — these specs align code
  with that normative model.

## Test plan

- [x] All 5 spec files parse as EDN (implicit: pre-commit hooks green)
- [ ] Miniforge run of spec #1 lands a PR that makes the remaining specs
      themselves DAG-decomposable
- [ ] After #1 lands, re-running polylith-compliance-remediation shows
      `:workflow/dag-activated` event and parallel child workflows
