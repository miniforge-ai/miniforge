<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# I-DAG-MULTI-PARENT-MERGE — Multi-Parent DAG Merge Strategy (v2 of Per-Task Base Chaining)

**Status:** Informative — design draft, awaiting iteration before implementation.
**Date:** 2026-05-03
**Version:** 0.1.0

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

Three properties this preserves:

1. **Each task still has one base branch from its sub-workflow's
   perspective.** No protocol change to `acquire-environment!` /
   `task-sub-opts` / the scratch-worktree contract.
2. **The merge happens once, at orchestration time.** Sub-agents never see
   half-merged state.
3. **Failure is loud.** Conflicts surface as a typed anomaly with the
   conflicting parents and paths, not silent overwrites.

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
git cherry-pick parent-B-tip..parent-A-merge-base
git cherry-pick parent-C-tip..parent-A-merge-base
```

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

| Strategy keyword               | Behavior                                             |
| ------------------------------ | ---------------------------------------------------- |
| `:octopus` (default)           | §4.1; fall back to anomaly on conflict (§6).         |
| `:sequential-cherry-pick`      | §4.2; same fallback.                                 |
| `:fail-on-multi-parent`        | Today's v1 behavior — emits the dag-non-forest      |
|                                | anomaly. Useful for plans that **must** be forests   |
|                                | (e.g. release trains).                              |

`:last-parent-wins` is intentionally not exposed.

---

## 6. Failure Modes

Multi-parent merges have three failure shapes the orchestrator must surface
as typed anomalies, not let escape as raw git errors:

### 6.1 Merge conflict

```clj
{:anomaly/category :anomalies/dag-multi-parent-conflict
 :anomaly/message  "Octopus merge of N parents conflicted on M paths"
 :task/id          ...
 :merge/parents    [{:task/id ... :branch ...} ...]
 :merge/conflicts  [{:path ... :parents [...]} ...]
 :merge/strategy   :octopus}
```

The orchestrator MUST NOT auto-resolve. Auto-resolution (e.g.
`-X ours` / `-X theirs`) chooses arbitrarily and reproduces the v0 silent-
overwrite class. Surface the conflict, fail the task, let a human or a
follow-up agent decide.

### 6.2 Ancestor parent

If parent B is an ancestor of parent A (i.e., B's tip is reachable from A),
B contributes nothing. Treat as single-parent against A; emit
`:dag/multi-parent-ancestor-collapsed` log so the user knows the plan was
implicitly simpler than declared.

### 6.3 Empty parent intersection

When parents have a merge-base but the merge is a no-op (parents are
identical), proceed as if single-parent against any of them. No anomaly;
log only.

---

## 7. Integration Points (where it lands in the code)

All on the dag-executor / dag-orchestrator stack from PR #755:

- **`branch-registry`** (`components/dag-executor/src/.../branch_registry.clj`):
  - Replace `resolve-base-branch`'s multi-parent anomaly path with a
    `resolve-multi-parent-base` that returns
    `{:strategy ... :parents [{:task/id ... :branch ...}]}`. The actual
    merge is the orchestrator's job; the registry only resolves
    *which* branches to merge.
  - Drop the v1 forest-only anomaly. Keep `validate-forest` /
    `forest?` as informational helpers for plan-quality reporting.

- **`dag-orchestrator`** (`components/workflow/src/.../dag_orchestrator.clj`):
  - New helper `merge-parent-branches!` that performs the chosen strategy
    in a temporary worktree, returns either a freshly-created
    `task-N-base` branch ref or one of the §6 anomalies.
  - `task-sub-opts` calls it for multi-parent tasks; `:branch` becomes
    the merged ref's name. Single-parent tasks keep their current path
    unchanged.
  - `execute-plan-as-dag` no longer rejects non-forest plans by default.
    It rejects when the plan opts in via
    `:plan/strict-forest? true`, preserving today's behavior for callers
    that need it (release trains, formal-verification batches).

- **Plan schema:**
  - New optional `:task/merge-strategy` enum.
  - New optional plan-level `:plan/strict-forest?` boolean.

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
  construction (the validator rejected anything else); they keep working.
- **Rollback path:** set `:plan/strict-forest? true` at plan time, or
  default the strategy to `:fail-on-multi-parent` via config. Either
  reverts the orchestrator to v1 behavior.
- **Test additions:**
  - Unit: `merge-parent-branches!` happy path / each anomaly shape /
    each strategy.
  - Integration: 4-task diamond plan running end-to-end through the
    orchestrator with mocked parent branches on a real test git repo.

---

## 10. Open Questions

1. **Should the merged base be persisted as a permanent branch, or only
   exist for the lifetime of the task's worktree?** Permanent makes resume
   easier; ephemeral keeps the branch namespace clean. Lean ephemeral; the
   commit is reachable from the task's persisted branch via parentage.

2. **For `:sequential-cherry-pick`, what order do we apply parents?**
   Probably plan-declaration order (deterministic, author-controlled).
   Need to specify.

3. **Octopus refuses on criss-cross histories.** Do we silently fall back
   to recursive (two-parent merges chained), or surface the topology and
   ask the user? Lean fall-back-with-log; criss-cross is rare on
   well-decomposed work but real.

4. **Conflict-resolution agent loop?** Future-future: when octopus fails,
   spawn a sub-workflow whose only job is to land a merge commit
   resolving the conflict, then re-attempt. Out of scope for v2 but the
   anomaly shape in §6.1 should leave room for it.

5. **What's the right metric for "this is a strict release train, fail
   on multi-parent"?** A workflow-level config, a per-plan flag, both?
   Lean per-plan flag; release-train workflows can default-set it.

---

## 11. Out of Scope

- Conflict-resolution agent loops (see §10.4).
- Cross-repo / multi-repo DAGs (orthogonal; covered by repo-dag).
- Plan-time DAG simplification (e.g., auto-linearizing fan-in into chains
  when the parents are commutative). Could be a v3 optimization.
- N-way diff visualization / merge-conflict UI on the dashboard.

---

## 12. Summary

The dogfood proved per-task base chaining v1 works: zero token waste, the
gate fires before any task runs. It also proved the v1 forest restriction
blocks every non-trivial spec, because real plans fan in. v2 closes that
gap by performing a deterministic octopus merge of multi-parent task bases
at orchestration time, with a per-task override and an explicit fail-loud
mode for callers that need v1 strictness.

The work this spec covers is roughly:

1. Extend the plan schema with `:task/merge-strategy` and
   `:plan/strict-forest?`.
2. Replace `resolve-base-branch`'s multi-parent anomaly with the new
   `resolve-multi-parent-base` data shape.
3. Implement `merge-parent-branches!` in the orchestrator (octopus +
   sequential-cherry-pick).
4. Wire `task-sub-opts` to call the new helper.
5. Update `execute-plan-as-dag` to gate on `:plan/strict-forest?`.
6. Tests + a re-run of the 2026-05-03 dogfood to prove the gap closes.
