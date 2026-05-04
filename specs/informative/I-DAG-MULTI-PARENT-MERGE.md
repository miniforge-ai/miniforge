<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# I-DAG-MULTI-PARENT-MERGE — Multi-Parent DAG Merge Strategy (v2 of Per-Task Base Chaining)

**Status:** Informative — iteration round 1 complete; ready for implementation.
**Date:** 2026-05-03
**Version:** 0.2.0

**Changelog:**

- **0.2.0** — Iteration round 1: settled all five §10 open questions.
  Branches now ephemeral (Q1). Cherry-pick order pinned to plan
  declaration (Q2). Criss-cross histories silently fall back to
  recursive merges (Q3). **Conflict-resolution agent loop pulled
  in-scope** (Q4) — both pre-task octopus failures and mid-PR-
  lifecycle MERGEABLE→NON_MERGEABLE transitions trigger an automated
  resolution sub-workflow rather than failing to a human.
  **Strict-forest opt-out flag dropped entirely** (Q5) — v2's
  always-on conflict handling makes plan-time fail-loud unnecessary.
- **0.1.0** — Initial draft.

Specifies how a DAG task with multiple completed parents acquires a base for its
scratch worktree. Closes the gap left open by per-task base chaining v1
([PR #755](https://github.com/miniforge-ai/miniforge/pull/755) /
[spec #732](https://github.com/miniforge-ai/miniforge/issues/732)), which
explicitly punted multi-parent support and rejected non-forest plans at
plan-validation time.

---

## 1. Purpose

Allow a DAG task whose `:task/dependencies` list includes more than one entry
to start its sub-workflow off a base that **incorporates work from every
parent**, not just one. Without this, real specs cannot run end-to-end on
miniforge — see §2.

---

## 2. Evidence — Why v1 is Too Strict

**Dogfood, 2026-05-03**, against
`specs/informative/I-CLASSIFICATION-RUNTIME.md` from a fresh main with
per-task base chaining wired in:

- The plan agent decomposed the spec into **9 tasks across 5 strata**.
- Two tasks were legitimately multi-parent:
  - `agent-runtime cleanup` depended on both `engine` and
    `failure-classifier migration` — semantically correct (it needs both
    surfaces to delegate to).
  - `candidate domain evaluation` depended on multiple prior steps —
    semantically correct (the "have we proven the pattern?" task fans in
    from the migrations).
- v1's plan-time forest validator rejected the plan with
  `:anomalies/dag-non-forest`. **Zero tasks ran.** Total cost 0 tokens,
  duration 5.9m (mostly the plan agent itself).

The forest validator did exactly what the v1 spec promised — short-circuit
non-forest plans early. The signal it surfaced is that the restriction is
too strict for real workloads. **Real specs naturally produce fan-in.**

---

## 3. Core Design Principle

> A multi-parent DAG task starts on a base that is the **deterministic merge
> of all its parents' persisted branches**. The merge is performed by the
> orchestrator at the boundary where v1 currently aborts; the sub-workflow
> sees a single ref like any single-parent task does.

Four properties this preserves:

1. **Each task still has one base branch from its sub-workflow's
   perspective.** No protocol change to `acquire-environment!` /
   `task-sub-opts` / the scratch-worktree contract.
2. **The merge happens once, at orchestration time.** Sub-agents never see
   half-merged state.
3. **Conflicts are resolved automatically, not punted to humans.** Both
   pre-task octopus failures and mid-PR-lifecycle
   MERGEABLE→NON_MERGEABLE transitions trigger a resolution sub-workflow
   (§6.1) rather than blocking on a human. HIL is a fallback for the
   resolution sub-workflow itself failing, not the default path. Each
   incidental HIL ask makes miniforge less useful.
4. **Failure is loud when it does occur.** When the resolution sub-
   workflow can't produce a clean merge, the result surfaces as a typed
   anomaly with the conflicting parents and paths — never as a silent
   overwrite.

### 3.1 Determinism

"Deterministic" means the same plan + the same parent commits always
produces the same merge commit, including the same git parent ordering
in that commit. The ordering source is the task's
`:task/dependencies` vector **as authored in the plan**, not the runtime
completion order of those parents. Concretely:

- The first parent of the merge commit is `:task/dependencies[0]`'s
  registered branch — this becomes the base (`git checkout -b ... <p0>`).
- Subsequent parents are passed to `git merge` in
  `:task/dependencies[1..N]` order.
- Octopus merges are commutative for non-conflicting changes (git
  computes the same tree regardless of the order of the second-and-after
  parents), so the order matters only for the human-visible parent
  sequence in the merge commit; we pin it anyway so logs and history
  match across replays.
- If `:task/dependencies` is a set rather than a vector at the schema
  layer (the plan/task schema currently allows either), the orchestrator
  sorts by `str` of the task-id before merging — same convention as
  `task-defs->forest-shape` in the orchestrator today, which sorts deps
  for deterministic anomaly payloads.

---

## 4. Strategy Options

Four strategies were considered. The recommendation in §5 is to ship one as
default and allow per-task override via plan annotation.

### 4.1 Octopus merge (recommended default)

```bash
git checkout -b task-N-base parent-A
git merge parent-B parent-C ...           # one commit
```

**Pros:**

- Native git operation. Single merge commit reflects fan-in faithfully.
- Conflict-detection is git's, not ours — well-understood semantics.
- Round-trips cleanly through `git push` / GitHub.
- Cheap when parents touch disjoint files (the common case for
  well-decomposed work).

**Cons:**

- Octopus refuses on any conflict. We need a fallback path (see §6).
- Octopus disallows criss-cross merges in some git versions; needs a
  topology check up front.

### 4.2 Sequential cherry-pick

```bash
git checkout -b task-N-base parent-A
# Cherry-pick the commits unique to each non-chosen parent.
# git's A..B selects commits reachable from B but not A, so the range
# is (merge-base ↔ parent-tip), NOT the reverse.
git cherry-pick $(git merge-base parent-A parent-B)..parent-B
git cherry-pick $(git merge-base parent-A parent-C)..parent-C
```

Parents are applied in **plan-declaration order** (same source as octopus
parent ordering — see §3.1). Deterministic, author-controlled, and
matches what an author would write if they listed deps in priority order.

**Pros:**

- Linear history.
- Per-commit conflict resolution is easier to reason about than an n-way
  octopus when conflicts do happen.

**Cons:**

- Loses the fan-in shape — history reads as if the work were sequential.
- O(commits-in-each-parent) operations per merge, slower than octopus on
  big parent branches.
- Cherry-pick conflicts on shared ancestors can be subtle.

### 4.3 Last-parent-wins

Pick the parent that completed most recently; ignore the others. Each
non-chosen parent's work is silently dropped from the base.

**Pros:**

- Trivial to implement; matches current single-parent code path exactly.

**Cons:**

- Reproduces the v0 bug (sibling tasks not seeing upstream output) for
  every parent except one. **Rejected.** Listed only for completeness.

### 4.4 Plan-annotated strategy

Plan emits `:task/merge-strategy` per multi-parent task; default to one of
4.1 / 4.2 when absent.

**Pros:**

- Lets plan authors override per task when they know better than the
  default (e.g. force cherry-pick when they expect conflicts).
- Backwards-compatible — existing plans without the key get the default.

**Cons:**

- Adds a key to the plan schema. Plan agents must learn to emit it.

---

## 5. Recommendation

**Default to 4.1 octopus merge. Allow per-task override via 4.4
`:task/merge-strategy`.** Concretely:

| Strategy keyword          | Behavior                                          |
| ------------------------- | ------------------------------------------------- |
| `:octopus` (default)      | §4.1; on conflict, spawn resolution sub-          |
|                           | workflow per §6.1 — does NOT fail to a human.     |
| `:sequential-cherry-pick` | §4.2; same conflict path.                         |

`:last-parent-wins` is intentionally not exposed (would reproduce the
v0 silent-overwrite class).

**No `:fail-on-multi-parent` mode.** The v0.1 draft proposed a
`:plan/strict-forest?` opt-in that emitted the v1
`:anomalies/dag-non-forest` anomaly. The iteration round dropped it:
v2's always-on automated conflict resolution makes plan-time fail-loud
unnecessary, and it muddied the contract — every additional knob is a
foot-gun. If a workflow truly needs linear history (formal-verification
batches, regulatory pipelines), the right answer is to author plans
that are forests by construction, not to add a runtime mode that turns
the orchestrator back into v1.

---

## 6. Conflict Handling and Edge Cases

Multi-parent merges have one **conflict path** that triggers an automated
resolution sub-workflow (§6.1) and two **degenerate edge cases** where
the merge succeeds trivially but the topology is informational and worth
logging (§6.2/§6.3). The two are deliberately separated: §6.1 invokes
real work, edge-case logs do not.

A second conflict surface — mid-PR-lifecycle MERGEABLE→NON_MERGEABLE
transitions — is handled by the same resolution path; see §6.4.

### 6.1 Merge conflict — automated resolution

When octopus (or `:sequential-cherry-pick`) hits a conflict, the
orchestrator does NOT auto-pick `-X ours` / `-X theirs` (would reproduce
v0 silent overwrites) and does NOT fail to a human. It spawns a
**resolution sub-workflow** seeded with:

```clojure
{:resolution/parents    [{:task/id ... :branch ...} ...]
 :resolution/conflicts  [{:path ... :parents [...]} ...]
 :resolution/strategy   :octopus  ; or :sequential-cherry-pick
 :resolution/parent-task :task/id ; the multi-parent task this is for
 :resolution/budget     <iterations | tokens>}
```

The resolution sub-workflow's contract:

- **Goal:** produce a merge commit that resolves all conflicts and
  passes the task's verify gates.
- **Pipeline:** `implement` (agent edits the conflicted files) →
  `verify` (project tests) → terminate when verify passes or budget
  exhausts.
- **Output (success):** a ref name on the task's persisted branch, with
  the merge commit at the tip. The orchestrator continues the original
  task using this ref as `:branch`.
- **Output (failure, budget exhausted or verify never passes):** the
  terminal anomaly below — only path that surfaces to a human.

```clojure
{:anomaly/category :anomalies/dag-multi-parent-unresolvable
 :anomaly/message  "Octopus merge conflict could not be auto-resolved within budget"
 :task/id          ...
 :merge/parents    [{:task/id ... :branch ...} ...]
 :merge/conflicts  [{:path ... :parents [...]} ...]
 :merge/strategy   :octopus
 :resolution/last-attempt-ref ...
 :resolution/reason :budget-exhausted | :verify-never-passed}
```

The unresolvable anomaly carries the last-attempt ref so an operator
inspecting the dashboard can see exactly what state the resolution
agent left the merge in.

### 6.2 Ancestor parent (informational, not an anomaly)

If parent B is an ancestor of parent A (i.e., B's tip is reachable from A),
B contributes nothing — A already includes B's work. The merge is a no-op
relative to A. Treat as single-parent against A; emit a
`:dag/multi-parent-ancestor-collapsed` log entry naming the collapsed
parent and the parent it collapsed into, so the operator knows the plan
was implicitly simpler than declared. **No anomaly** — the merge
succeeded; this case only matters for plan-quality observability.

### 6.3 Empty parent intersection (informational, not an anomaly)

When parents share a merge-base and the merge is a no-op because the
parents are identical (no commits unique to any of them since the merge-
base), proceed as if single-parent against any of them. Emit a
`:dag/multi-parent-no-op` log entry. **No anomaly** — the merge
succeeded.

### 6.4 Mid-PR-lifecycle conflict (NON_MERGEABLE)

A PR that was MERGEABLE when opened can drift to NON_MERGEABLE when
something else lands on the base branch first. The release-flow PR
lifecycle today is:

```text
open PR
  → respond to comments (fix, push, resolve)
  → wait for comments to settle
  → ensure GitHub merge-state is MERGEABLE
  → merge
```

A NON_MERGEABLE state at the "ensure mergeable" gate is the same
problem class as §6.1: a multi-parent fan-in (the PR's branch + main's
new state) with conflicts. v2 reuses the §6.1 resolution sub-workflow
verbatim — same input shape, same agent contract, same terminal
anomaly. The only differences:

- **Trigger:** PR-lifecycle monitor detects the MERGEABLE→NON_MERGEABLE
  transition rather than an octopus-merge fault.
- **Parent set:** `[{:branch <pr-branch>} {:branch <pr-base>}]` —
  always two-parent, never N-parent.
- **Output success:** push the resolution commit onto the PR branch;
  let GitHub re-evaluate mergeability; resume the lifecycle.

Sharing the resolution sub-workflow across the two surfaces is the
whole point: one merge-conflict-resolution agent, two trigger sites.
Without this, miniforge would have a hole in the release flow large
enough to make every multi-day workflow fragile.

---

## 7. Integration Points (where it lands in the code)

Five surfaces touch:

- **`branch-registry`** (`components/dag-executor/src/.../branch_registry.clj`):
  - Replace `resolve-base-branch`'s multi-parent anomaly path with a
    `resolve-multi-parent-base` that returns
    `{:strategy ... :parents [{:task/id ... :branch ...}]}`. The actual
    merge is the orchestrator's job; the registry only resolves
    *which* branches to merge.
  - Drop the v1 forest-only anomaly. Keep `validate-forest` /
    `forest?` as informational helpers for plan-quality reporting.

- **`dag-orchestrator`** (`components/workflow/src/.../dag_orchestrator.clj`):
  - New helper `merge-parent-branches!` that performs the chosen
    strategy in an **ephemeral** worktree (see §11), returns either the
    new merge-commit ref OR triggers the resolution sub-workflow per
    §6.1 on conflict.
  - `task-sub-opts` calls it for multi-parent tasks; `:branch` becomes
    the merge-commit ref. Single-parent tasks keep their current path
    unchanged.
  - `execute-plan-as-dag` no longer rejects non-forest plans. v1's
    forest validator is dropped from the gate path; `validate-forest`
    survives as an informational helper.

- **Resolution sub-workflow** (new — likely a workflow definition under
  `components/workflow/src/.../merge_resolution.clj` or a specialized
  invocation of the standard implement/verify pipeline with a curated
  agent prompt). Inputs and outputs per §6.1. Reused by both the
  pre-task path (§6.1 trigger) and the PR-lifecycle path (§6.4 trigger).

- **`pr-lifecycle`** (`components/pr-lifecycle/src/...`):
  - The PR monitor loop already detects state transitions. Hook the
    MERGEABLE→NON_MERGEABLE transition to invoke the resolution
    sub-workflow with the two-parent input shape from §6.4. On success,
    push the resolution commit and let the lifecycle resume. On
    `dag-multi-parent-unresolvable`, surface to the human review path
    that already exists for other lifecycle failures.

- **Plan schema:**
  - New optional `:task/merge-strategy` enum
    (`#{:octopus :sequential-cherry-pick}`).
  - **No** `:plan/strict-forest?` flag (dropped per §5).

---

## 8. Why Not Defer to Manual Merging?

A reviewer might suggest: when the orchestrator hits a multi-parent task,
just push the parent branches and let a human merge them upstream first.

This re-introduces the v0 dogfood problem one level up: the orchestrator
stops being able to drive a real spec to completion without human
intervention. Multi-parent fan-in is not exotic; it's the natural shape of
"now wire these two pieces together" tasks. Automating the merge — and
failing loudly when it can't — is the table stakes for autonomous
multi-task workflows.

---

## 9. Migration / Rollback

- **No data migration.** Plans authored under v1 are forests by
  construction (the validator rejected anything else); they keep working
  unchanged in v2.
- **No rollback flag.** v0.1 proposed a `:plan/strict-forest?` opt-in
  preserving v1 behavior; iteration round 1 dropped it (§5). If a
  workflow truly needs forest-only, it should be authored as a forest;
  no runtime mode duplicates the constraint.
- **Test additions:**
  - Unit: `merge-parent-branches!` happy path / each anomaly shape /
    each strategy / criss-cross fallback path (§10.3).
  - Integration: 4-task diamond plan running end-to-end through the
    orchestrator with mocked parent branches on a real test git repo.
  - Integration: induced merge conflict that the resolution sub-workflow
    succeeds on (round-trip test of §6.1 happy path).
  - Integration: induced merge conflict that exhausts the resolution
    budget (round-trip test of the `dag-multi-parent-unresolvable`
    anomaly).

---

## 10. Iteration Round 1 — Resolved Decisions

The five §10 questions in v0.1 are all settled in v0.2. Captured here
for the audit trail; the design above already reflects the answers.

### 10.1 Merged base lives on an ephemeral branch

- **Decision:** ephemeral.
- **Rationale:** the merge commit is reachable from the task's persisted
  branch via parentage — that's the contract the task makes when it
  pushes its branch with the merge as part of its history. On resume,
  the commit's parents (and its tree) are recoverable from the persisted
  branch alone; no separate ref is needed for checkpointing.
- **Implication for `merge-parent-branches!`:** create the merge commit
  in a temporary worktree on a temp ref, hand the commit-sha to the
  caller, then drop the temp ref. The branch namespace stays clean.
- **Mitigation if a future replay path needs the ref:** if we later
  discover that some replay or audit flow can't navigate to the merge
  commit purely via parentage, we add a deterministic `merges/<task-id>`
  ref written at the same time as the persisted branch. That's a
  schema-additive change; not a redesign.

### 10.2 Cherry-pick order = plan declaration order

- **Decision:** plan-declaration order, same source as octopus parent
  ordering (§3.1). Deterministic, author-controlled.

### 10.3 Criss-cross histories: silent fallback to recursive merges

- **Decision:** when octopus refuses on a criss-cross topology, the
  orchestrator silently chains two-parent recursive merges in
  declaration order (`merge p0 + p1 → tmp`, `merge tmp + p2 → tmp`, …)
  and emits a `:dag/multi-parent-recursive-fallback` log entry naming
  the criss-cross detection signal. **No HIL ask.** Each incidental HIL
  ask is a tax on miniforge's usefulness; criss-cross is rare on
  well-decomposed plans, and the fallback is well-defined.

### 10.4 Conflict-resolution agent loop is in-scope

- **Decision:** in-scope for v2, not future-future. v0.1 proposed
  surfacing conflicts as a terminal anomaly to a human; the user
  pointed out that this would let HIL drive everyday merge-conflict
  resolution, eroding miniforge's autonomy guarantee. The redesign
  (§6.1) makes the conflict path automated: spawn a resolution
  sub-workflow, only surface to a human when the sub-workflow itself
  exhausts its budget.
- **Reuse:** the same resolution sub-workflow handles the §6.4
  PR-lifecycle MERGEABLE→NON_MERGEABLE case. One agent, two trigger
  sites.

### 10.5 No strict-forest flag

- **Decision:** dropped entirely (§5). Per the user, "not sure why we
  would want this" — and they're right. v2's automated conflict
  resolution makes plan-time fail-loud unnecessary. Workflows that
  genuinely need linear history author plans as forests; no runtime
  mode duplicates the constraint.

---

## 11. Open Questions for Iteration Round 2

These surfaced during the round-1 design pass and weren't pre-existing
in v0.1.

1. **Resolution sub-workflow budget shape.** Iteration count? Token
   budget? Wall-clock? Lean iteration-bounded with a token cap, since
   that's how the rest of the implementer/verify loops are gated.
   Default budget needs picking. Lean small (e.g., 3 iterations) since
   merge-conflict resolution is a focused task — if the agent can't
   converge in a few rounds the conflict probably needs human eyes.

2. **Resolution sub-workflow's verify gate.** Project-test-suite seems
   right for the §6.1 pre-task path (the merge has to compile/test
   before the dependent task runs). For the §6.4 PR-lifecycle path,
   project tests are also right but slower than just "does git think
   it's mergeable now?" Should §6.4 short-circuit on
   GitHub-says-MERGEABLE before running tests, and only run tests
   if there's a substantive content change?

3. **Curator role for the resolution agent.** Current implement-phase
   curators check that files were actually written by the agent (the
   `:curator/no-files-written` failure mode). Does the resolution agent
   need a specialized curator that also checks the merge-conflict
   markers were resolved? Lean yes; otherwise budget exhaustion can
   happen with markers still in the tree.

4. **Per-task-id telemetry for the resolution path.** The
   per-task-base-chaining v1 events already carry `:task/id`; the
   resolution sub-workflow's events should be tagged with both the
   parent task-id (the multi-parent task whose merge needed resolving)
   and a sub-workflow id, so the dashboard can show "this task needed
   merge resolution for N iterations" without conflating it with the
   parent task's own implement work.

5. **Out-of-band conflicts on the persisted branch.** If a parent task
   force-pushes to its persisted branch between batch completion and
   the merge attempt (rare in normal flow, possible during retries),
   the merge sees a different tip than the registry recorded. Detect
   and refresh, or fail with a fresh anomaly? Lean detect-and-refresh
   with a log; matches the no-HIL-by-default principle from §10.3.

---

## 12. Out of Scope

- **Cross-repo / multi-repo DAGs** — orthogonal; covered by repo-dag.
- **Plan-time DAG simplification** — auto-linearizing fan-in into
  chains when the parents are commutative. Could be a v3 optimization
  once we have data on which fan-ins compose vs. conflict in practice.
- **N-way diff visualization / merge-conflict UI on the dashboard** —
  the resolution sub-workflow makes this less urgent (the dashboard
  shows the resolution agent's edits naturally), but a dedicated
  conflict view would be a nice-to-have once the substrate is in place.
- **Conflict-resolution agent loops** were in this list in v0.1; they
  are now in scope (§6.1 / §10.4).

---

## 13. Summary

The dogfood proved per-task base chaining v1 works: zero token waste,
the gate fires before any task runs. It also proved the v1 forest
restriction blocks every non-trivial spec, because real plans fan in.

v2 closes that gap by performing a deterministic octopus merge of
multi-parent task bases at orchestration time. When the merge
conflicts, an automated resolution sub-workflow takes the parents and
the conflicts as input and produces a clean merge commit; only when
the sub-workflow itself exhausts its budget does anything surface to
a human. The same sub-workflow handles mid-PR-lifecycle
MERGEABLE→NON_MERGEABLE transitions, so the release flow is closed
end-to-end without HIL touch points in the common case.

The work this spec covers, roughly:

1. Extend the plan schema with `:task/merge-strategy`
   (`#{:octopus :sequential-cherry-pick}`).
2. Replace `resolve-base-branch`'s multi-parent anomaly with the new
   `resolve-multi-parent-base` data shape; drop the v1 forest gate.
3. Implement `merge-parent-branches!` in the orchestrator (octopus +
   sequential-cherry-pick + criss-cross fallback to recursive).
4. Build the merge-conflict resolution sub-workflow (§6.1 contract).
5. Wire `task-sub-opts` to call `merge-parent-branches!` for
   multi-parent tasks.
6. Hook `pr-lifecycle`'s monitor loop on MERGEABLE→NON_MERGEABLE to
   invoke the resolution sub-workflow per §6.4.
7. Tests + a re-run of the 2026-05-03 dogfood (against the same
   `I-CLASSIFICATION-RUNTIME.md` plan) to prove the gap closes.
