<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# I-DAG-MULTI-PARENT-MERGE — Multi-Parent DAG Merge Strategy (v2 of Per-Task Base Chaining)

**Status:** Informative — iteration round 2 complete; ready for implementation.
**Date:** 2026-05-03
**Version:** 0.3.0

**Changelog:**

- **0.3.0** — Iteration round 2: settled all five §11 open questions
  (resolution budget shape, verify gate, curator role, telemetry
  tagging, out-of-band parent tip changes) AND folded in a technical
  review of v0.2 (Git semantics tightening). Major changes:
  - Renamed default strategy `:octopus` → `:git-merge`. Git uses
    `ort` for two-head merge and `octopus` only for 3+ heads;
    exposing `:octopus` as the user-facing default was misleading
    for the common two-parent case.
  - Replaced `:sequential-cherry-pick` with `:sequential-merge` as
    the alternate strategy — cherry-pick over parent branches that
    contain merge commits is unsafe without `--mainline` plumbing
    and loses merge-resolution history. Sequential merge preserves
    history correctly.
  - Tightened determinism: spec now claims "same effective merge
    result" (tree + parent ordering), not "same commit SHA". Pinned
    the merge flags (`--no-edit --no-gpg-sign --no-verify --no-ff
    -m <deterministic message>`) so config drift doesn't change
    behavior.
  - Registry shape now resolves parents to immutable SHAs at
    resolution time (not branch tips). Added `:sha`, `:order`, and
    `:merge/input-key` (idempotency key derived from task-id +
    strategy + ordered parent SHAs). Branch names are mutable; SHAs
    are not.
  - Specified the parent collapse / first-parent rule precedence
    explicitly (collapse duplicates and ancestors first, preserve
    plan order on remaining tips, then assign first-parent). Closes
    a contradiction in v0.2.
  - Renamed §6.3 from "empty parent intersection" to "duplicate /
    identical parent tips" — the prior name was misleading.
  - Added §6.5 unrelated-histories anomaly. Git refuses unrelated
    histories by default; v2 must NOT use `--allow-unrelated-
    histories` for normal DAG parent merges.
  - Adjusted the §6.1 conflict shape: `:merge/conflicts` entries now
    carry `:parent-candidates` and `:attribution` (precise pairwise
    attribution may not be available from octopus output). Added
    `:git/exit-code` and `:git/stderr` for raw diagnostics.
  - Round-2 outcomes documented in the new §10.6–§10.10. Notable:
    resolution-budget philosophy is "prevent exhaustion from
    stagnation" (small iteration cap, telemetry-driven optimization,
    not a hammer on token totals); a merge-resolution curator
    detects persistent conflicts; events use a hierarchical
    `<run>.<plan>.<task>.<resolution>` taxonomy.
- **0.2.0** — Iteration round 1: settled v0.1's five open questions.
  Branches ephemeral (Q1). Cherry-pick order pinned to plan
  declaration (Q2). Criss-cross histories silently fall back (Q3).
  **Conflict-resolution agent loop pulled in-scope** (Q4) — both
  pre-task failures and mid-PR-lifecycle NON_MERGEABLE transitions
  trigger an automated resolution sub-workflow.
  **Strict-forest opt-out flag dropped entirely** (Q5).
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

### 3.1 Determinism (what we promise vs. what git gives us)

"Deterministic" here means **same effective merge result** — same input
parent SHAs, same merge strategy, same first-parent choice, same
resulting tree — modulo Git version and config differences. It does
NOT mean the same commit SHA across runs. A Git commit object also
depends on author, committer, timestamps, message bytes, signing
config, and merge hooks; pinning a SHA across replays would require
pinning all of those, which is brittle and not worth the complexity
for v2.

The orchestrator pins what it CAN pin so config drift between
machines doesn't change behavior:

```bash
git merge --no-edit \
          --no-gpg-sign \
          --no-verify \
          --no-ff \
          -m "<deterministic generated message>" \
          <p1-sha> [<p2-sha> ...]
```

- `--no-edit` skips the editor.
- `--no-gpg-sign` defeats `commit.gpgsign=true` in user config.
- `--no-verify` skips merge / pre-commit hooks (the orchestrator
  runs its own verify gates downstream).
- `--no-ff` forces a real merge commit even when fast-forward is
  possible — without this, a single-effective-parent merge would
  produce no merge commit and the registry would have nothing to
  point at.

The first parent of the merge commit is the parent that the
worktree was checked out on (`git checkout -B <tmp> <p0-sha>`); its
identity is determined by §3.2.

Subsequent parents are passed to `git merge` in plan-declaration
order on the (possibly collapsed) parent set from §3.2.

If `:task/dependencies` is a set rather than a vector (current schema
allows either), the orchestrator sorts by `str` of the task-id before
collapsing — same convention as `task-defs->forest-shape` today.

### 3.2 Parent resolution and collapse algorithm

A multi-parent task's `merge-parent-branches!` runs this sequence:

1. **Resolve to SHAs.** For each declared dependency, look up the
   registered branch and snapshot its tip SHA at this moment. Subsequent
   force-pushes on the parent branch don't affect THIS task's merge —
   the SHAs are the immutable inputs (see §6.6 for force-push handling).
2. **Order normally.** If `:task/dependencies` is a vector, keep its
   order. If it's a set, sort by `str` of task-id. Call the result
   `[p0, p1, ..., pN-1]` of `{:task/id ... :branch ... :sha ...}`
   maps.
3. **De-duplicate identical tips.** Drop later entries whose `:sha`
   already appears earlier. Emit `:dag/multi-parent-no-op` log per
   §6.3 if any duplicates were dropped.
4. **Collapse ancestors.** Drop any parent whose tip is reachable
   from another parent's tip (`git merge-base --is-ancestor`).
   Preserve order among the surviving "maximal" tips. Emit
   `:dag/multi-parent-ancestor-collapsed` log per §6.2 for each
   collapsed parent, naming both the dropped parent and the parent
   that absorbed it.
5. **Decide the merge action.**
   - 0 effective parents: impossible (we wouldn't be here).
   - 1 effective parent: no merge needed; return that parent's SHA
     as the base. Single-parent fast path (matches v1's chained-
     base behavior exactly).
   - 2 effective parents: two-head merge via `-s ort`.
   - 3+ effective parents: octopus merge via `-s octopus`.
6. **Compute the idempotency key.** Hash
   `[:task-id, :strategy, [p0-sha, p1-sha, ...]]` to produce
   `:merge/input-key`. Used for §7's namespaced ref naming so
   replays of the same effective input reuse the same merge ref
   instead of accumulating new ones.
7. **Execute the merge** in a clean temporary worktree per §3.1.
8. **On success**, capture the resulting commit SHA and push under
   the namespaced ref (§7). Drop the temp worktree.
9. **On conflict**, hand the conflict information to the resolution
   sub-workflow per §6.1. The temp worktree's conflict state is
   serialized into the sub-workflow input rather than left on disk.

This sequencing resolves a contradiction in v0.2: §3.1 said
dep[0] is always the first parent, while §6.2 said an ancestor
parent collapses. The new explicit ordering is **collapse first,
then assign first-parent**, so an ancestor that would have been
dep[0] disappears and the surviving leftmost dep becomes the first
parent. No surprise.

### 3.3 Hierarchical event tagging

Events emitted by multi-parent merges (and by the resolution sub-
workflow) follow a `<run>.<plan>.<task>.<resolution>` namespacing
convention. Concretely each event carries:

```clojure
:event/path [<run-id> <plan-id> <task-id> <resolution-id-or-nil>]
```

- `run-id` is the workflow run.
- `plan-id` is the plan within that run.
- `task-id` is the multi-parent task whose merge needed resolving.
- `resolution-id` is the resolution sub-workflow's id when the event
  is from inside that sub-workflow; nil for orchestrator-level merge
  events.

The path produces a sortable key for the dashboard so per-task
merge-resolution work doesn't conflate with the parent task's own
implement work, AND the parent-child chain stays traceable end-to-end.

Implementation hint: a Snowflake-style ordered ID generator (with
run-id as the "datacenter" component and plan-id/task-id as the
upper-bit slots) would give the same ordering naturally. Either path
is acceptable; the contract is the dashboard-sortable
`<run>.<plan>.<task>.<resolution>` key.

---

## 4. Strategy Options

Four strategies were considered. The recommendation in §5 is to ship one as
default (§4.1) and allow per-task override via plan annotation (§4.4).

### 4.1 `:git-merge` (recommended default)

A single git merge invocation against the collapsed parent set from §3.2,
with `git` selecting `ort` for two-head merges and `octopus` for 3+:

```bash
# 2 effective parents (the common fan-in case):
git checkout -B <tmp> <p0-sha>
git merge -s ort     <pinned merge flags from §3.1> <p1-sha>

# 3+ effective parents:
git checkout -B <tmp> <p0-sha>
git merge -s octopus <pinned merge flags from §3.1> <p1-sha> <p2-sha> ...
```

The strategy keyword is `:git-merge` — NOT `:octopus`, which v0.2 used.
Git's `octopus` strategy is specifically for merging 3+ heads at once;
calling the user-facing default "octopus" was misleading for two-parent
fan-in (the most common shape in real plans). The internal sub-mode
(`ort` vs. `octopus`) is selected by parent count, not exposed to plan
authors.

**Pros:**

- Native git operation. Single merge commit, clear parent ordering.
- Conflict detection is git's, not ours — well-understood semantics.
- Round-trips cleanly through `git push` / GitHub.
- Cheap when parents touch disjoint files (the common case for
  well-decomposed work).

**Cons:**

- Octopus refuses on conflicts and on criss-cross histories. v2's
  conflict handling is the resolution sub-workflow (§6.1); criss-cross
  fallback is automatic recursive chaining (§10.8).

### 4.2 `:sequential-merge` (alternate)

Apply parents one at a time via two-parent merges, in plan-declaration
order:

```bash
git checkout -B <tmp> <p0-sha>
git merge -s ort <pinned merge flags> <p1-sha>
git merge -s ort <pinned merge flags> <p2-sha>
# ...
```

**Pros:**

- Each merge is a two-parent merge — `ort` is robust and well-
  characterized.
- Per-step conflict resolution is easier to reason about than an n-way
  octopus when conflicts do happen.
- Handles parent branches that themselves contain merge commits
  (those branches are intermediate fan-in points in the same plan)
  without special plumbing.
- Preserves merge-resolution history correctly (unlike cherry-pick;
  see §4.3).

**Cons:**

- History looks artificially serial — the resulting commit graph
  shows a chain of pairwise merges rather than a single fan-in.
- Slower than octopus when there are 3+ parents and no conflicts.

### 4.3 `:sequential-cherry-pick` — explicitly NOT supported in v2

v0.2 listed sequential cherry-pick as the alternate strategy. v0.3
removes it because:

- Cherry-picking a parent branch that contains merge commits requires
  `--mainline` to choose which side of the merge to apply — and v2
  has no principled way to choose. Plans that produce intermediate
  fan-in (which v2 explicitly enables!) will hit this immediately.
- Even on linear parent branches, ranges like
  `$(git merge-base p0 p1)..p1` re-apply patch-equivalent commits
  from earlier parents that the prior cherry-pick already landed.
  Avoiding this requires `rev-list --cherry-pick --right-only` plumbing,
  which interacts badly with the merge-commit case above.
- Cherry-pick discards merge-resolution edits that exist only in
  merge commits. Once miniforge produces fan-in branches, that's
  silent data loss.

If a future v3 wants linear-history plans, `:sequential-cherry-pick`
can be revisited under a stricter precondition (e.g.
`:dag-merge-strategy-unsupported-topology` anomaly when parent branches
contain merges). Out of scope for v2.

### 4.4 Plan-annotated strategy

Plan emits `:task/merge-strategy` per multi-parent task; default to
`:git-merge` when absent.

**Pros:**

- Lets plan authors override per task when they know better than the
  default (e.g. force `:sequential-merge` when they expect conflicts
  and want easier per-step resolution).
- Backwards-compatible — existing plans without the key get the
  default.

**Cons:**

- Adds a key to the plan schema. Plan agents must learn to emit it.

### 4.5 Last-parent-wins

Pick the parent that completed most recently; ignore the others.
**Rejected.** Reproduces the v0 silent-overwrite bug for every parent
except one. Listed only for completeness.

---

## 5. Recommendation

**Default to `:git-merge`. Allow per-task override via
`:task/merge-strategy`.** Concretely:

| Strategy keyword       | Behavior                                              |
| ---------------------- | ----------------------------------------------------- |
| `:git-merge` (default) | §4.1; `ort` for 2 effective parents, `octopus` for    |
|                        | 3+. On conflict, spawn resolution sub-workflow per    |
|                        | §6.1 — does NOT fail to a human.                      |
| `:sequential-merge`    | §4.2; pairwise merges in plan-declaration order.      |
|                        | Same conflict path.                                   |

`:sequential-cherry-pick` and `:last-parent-wins` are intentionally not
exposed. See §4.3 / §4.5.

**No `:fail-on-multi-parent` mode.** v0.1 proposed a
`:plan/strict-forest?` opt-in; v0.2 dropped it. v2's automated conflict
resolution makes plan-time fail-loud unnecessary. Workflows that
genuinely need linear history author plans as forests; no runtime mode
duplicates the constraint.

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

When `:git-merge` (or `:sequential-merge`) hits a conflict, the
orchestrator does NOT auto-pick `-X ours` / `-X theirs` (would reproduce
v0 silent overwrites) and does NOT fail to a human. It spawns a
**resolution sub-workflow** seeded with:

```clojure
{:resolution/parents    [{:task/id ... :branch ... :sha ... :order ...} ...]
 :resolution/conflicts  [{:path                ...
                          :parent-candidates   [...]   ; task-ids whose tips touch this path
                          :attribution         :all-candidates ; or :pairwise-derived
                          } ...]
 :resolution/strategy        :git-merge   ; or :sequential-merge
 :resolution/parent-task     :task/id     ; the multi-parent task this is for
 :resolution/input-key       <hash>       ; from §3.2 step 6
 :resolution/git-exit-code   <int>
 :resolution/git-stderr      "..."
 :resolution/budget          {:max-iterations N
                              :stagnation-cap M}}
```

A few notes on the shape:

- **`:parent-candidates` instead of `:parents`.** Octopus / sequential-
  merge output doesn't always tell us "these exact two parents
  conflicted on this path." Conservative attribution — "this path
  conflicted, and these are the parent tips that touched it" — is
  what the orchestrator can reliably emit. The resolution agent has
  enough information to investigate (the path, the parent tips, the
  raw git stderr); it doesn't need a guaranteed minimal pair.
- **`:attribution` is honest.** `:all-candidates` is the default
  (we listed every parent whose tip touched the path). If a future
  v2.x runs pairwise diagnostic merges to narrow down the responsible
  pair, that switches to `:pairwise-derived`.
- **`:git/exit-code` and `:git/stderr`** carry the raw git output so
  the resolution agent has direct access to whatever git thought went
  wrong, not just our re-projection of it.

The resolution sub-workflow's contract:

- **Goal:** produce a merge commit that resolves all conflicts AND
  passes the task's verify gates.
- **Pipeline:** `implement` (agent edits the conflicted files) →
  `verify` (project tests + the same hard gates the task itself
  would have run; see §6.1.1 for the policy) → terminate when verify
  passes or budget exhausts.
- **Curator:** specialized merge-resolution curator (§6.1.2) checks
  that conflict markers are gone AND that the resolution isn't
  recurring (same conflict, same paths, no progress). Recurring
  conflicts trigger early termination instead of burning the full
  budget on a stuck loop.
- **Output (success):** a ref name on the namespaced merge-base ref
  (§7), with the resolution commit at the tip. The orchestrator
  continues the original task using this ref as `:branch`.
- **Output (failure):** the terminal anomaly below — only path that
  surfaces to a human.

```clojure
{:anomaly/category :anomalies/dag-multi-parent-unresolvable
 :anomaly/message  "Merge conflict could not be auto-resolved"
 :task/id          ...
 :merge/parents    [{:task/id ... :branch ... :sha ... :order ...} ...]
 :merge/conflicts  [{:path ... :parent-candidates [...] :attribution ...} ...]
 :merge/strategy   :git-merge  ; or :sequential-merge
 :merge/input-key  <hash>
 :resolution/last-attempt-ref ...
 :resolution/reason :budget-exhausted
                   | :verify-never-passed
                   | :curator/recurring-conflict
                   | :curator/markers-not-resolved}
```

#### 6.1.1 Verify gate policy

The verify gate inside the resolution sub-workflow runs exactly what
the parent task's verify phase would run — project tests plus any
policy-enforced hard gates (in miniforge-local, the pre-commit chain;
in governed mode, whatever policy attaches to the workflow). This
matches the user-stated rule that "if the PR monitor loop makes
changes re: comment, it moves through verification."

For the §6.4 PR-lifecycle path the same rule applies, with one
optimization: if GitHub already reports `MERGEABLE` after pushing
the resolution commit AND the diff is purely conflict-marker
deletions (no semantic change), the verify gate can short-circuit
on the GitHub state alone. Any substantive change reaches the full
verify pipeline. This is a round-2 question 2 outcome — see §10.7.

#### 6.1.2 Merge-resolution curator

Curators in miniforge today check that the agent actually did the
work it was supposed to (e.g. `:curator/no-files-written`). The
merge-resolution curator extends the pattern with two additional
checks:

1. **`:curator/markers-not-resolved`** — the agent terminated but
   conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`) are still in
   the working tree. Treat as immediate failure of this iteration;
   if it recurs, escalate to terminal anomaly per §6.1.
2. **`:curator/recurring-conflict`** — the conflicted file set
   from this iteration matches the previous iteration's set
   (same paths, same conflict markers post-edit). The agent isn't
   making progress; escalate to terminal anomaly to avoid burning
   the rest of the budget on a stuck loop. This is the Round 2 Q3
   answer — see §10.8.

Both checks are pure-data inspections of the worktree state; they
don't need the agent's narration to fire.

### 6.2 Ancestor parent (informational, not an anomaly)

If parent B is an ancestor of parent A (i.e., B's tip is reachable from A),
B contributes nothing — A already includes B's work. The merge is a no-op
relative to A. Treat as single-parent against A; emit a
`:dag/multi-parent-ancestor-collapsed` log entry naming the collapsed
parent and the parent it collapsed into, so the operator knows the plan
was implicitly simpler than declared. **No anomaly** — the merge
succeeded; this case only matters for plan-quality observability.

### 6.3 Duplicate / identical parent tips (informational, not an anomaly)

(v0.2 called this "empty parent intersection" — misleading. The case
described here is parents-are-the-same, not parents-have-no-shared-
history. The unrelated-histories case is §6.5.)

When parents share a merge-base and the §3.2 collapse step finds
identical tips (one parent's `:sha` equals another's), the merge is a
no-op. Drop the duplicate, proceed as if single-parent against the
remaining tip. Emit a `:dag/multi-parent-no-op` log entry. **No
anomaly** — the merge succeeded trivially.

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

### 6.5 Unrelated histories (anomaly)

If the §3.2 collapse step finds parents that share NO common ancestor
(`git merge-base` returns nothing), git would refuse the merge by
default. v2 explicitly does NOT use `--allow-unrelated-histories` for
DAG parent merges — that flag exists for one-time repo grafts, not
for routine fan-in. Surface the situation as a typed anomaly:

```clojure
{:anomaly/category :anomalies/dag-multi-parent-unrelated-histories
 :anomaly/message  "Parents share no common ancestor — refusing to merge"
 :task/id          ...
 :merge/parents    [{:task/id ... :branch ... :sha ... :order ...} ...]
 :merge/strategy   :git-merge}
```

This is a plan-quality / repo-state anomaly, not a routine conflict.
The resolution sub-workflow does NOT auto-engage on this category —
unrelated histories almost always mean someone did something the
orchestrator wasn't expecting (a stray `git init`, a force-push that
rewrote a parent's entire history, an accidental cross-repo branch).
Surface to a human via the normal anomaly path.

### 6.6 Out-of-band parent tip change (round-2 question 5)

The §3.2 step 1 snapshot pins each parent's `:sha` at resolution
time. If a parent task's branch is force-pushed between resolution
and the orchestrator's actual merge attempt — rare in normal flow,
possible during retries — the merge runs against the SHA we
snapshotted, not the branch's current tip. That's the right
behavior: it preserves determinism and idempotency.

What if the snapshot is stale by the time we register the merge
result? Two cases per the user's round-2 answer:

- **Simple to refresh** (the snapshotted SHA is still reachable
  from the current tip — the parent did a normal push, not a
  history-rewriting force-push): emit a
  `:dag/multi-parent-parent-tip-advanced` log naming the old and
  new tips and proceed using the snapshotted SHA. The tip move is
  informational; the merge is unaffected.
- **Non-deterministic / policy violation** (the snapshotted SHA
  is no longer reachable — history was rewritten): surface as

  ```clojure
  {:anomaly/category :anomalies/dag-multi-parent-parent-rewritten
   :anomaly/message  "Parent branch was rewritten; snapshot SHA unreachable"
   :task/id          ...
   :merge/parent     {:task/id ... :branch ... :snapshot-sha ... :current-sha ...}}
  ```

  The orchestrator does not retry automatically — the rewrite was
  out-of-band, and silently re-snapshotting would let upstream
  drift quietly mutate the merge inputs. Surface to a human for a
  conscious decision (re-resolve the parent? cancel the dependent
  task? roll back the force-push?).

---

## 7. Integration Points (where it lands in the code)

Five surfaces touch.

### 7.1 `branch-registry` (`components/dag-executor/src/.../branch_registry.clj`)

- Replace `resolve-base-branch`'s multi-parent anomaly path with
  `resolve-multi-parent-base`, which returns the §3.2 normalized
  result:

  ```clojure
  {:merge/strategy   :git-merge       ; or :sequential-merge
   :merge/parents    [{:task/id ... :branch ... :sha ... :order 0}
                      {:task/id ... :branch ... :sha ... :order 1} ...]
   :merge/input-key  <hash>}
  ```

  The actual merge is the orchestrator's job; the registry only
  resolves *which* SHAs to merge and in what order.
- Drop the v1 forest-only anomaly. Keep `validate-forest` /
  `forest?` as informational helpers for plan-quality reporting (the
  dashboard can still surface "this plan has fan-in at task X" as
  metadata even though it's no longer a hard rejection).

### 7.2 `dag-orchestrator` (`components/workflow/src/.../dag_orchestrator.clj`)

New helper `merge-parent-branches!` running this algorithm:

```clojure
(defn merge-parent-branches!
  "Multi-parent merge per the §3.2 algorithm. Returns either
   {:merge/ok? true :merge/ref <ref-name> :merge/sha <sha>}
   or one of the §6 anomalies."
  [{:keys [run-id task-id strategy parents]}]
  ;; parents are already SHA-pinned and order-normalized
  ;; per resolve-multi-parent-base.
  ...)
```

Step-by-step:

1. **Validate** all parents have `:sha` set (resolution-time snapshots,
   not lazy lookups).
2. **Collapse** duplicates (§6.3) and ancestors (§6.2). If 0 or 1
   effective parent remains: no merge needed; return the surviving
   SHA. If unrelated histories: §6.5 anomaly.
3. **Compute the namespaced ref name** from `:merge/input-key`:

   ```text
   refs/miniforge/dag-base/<run-id>/<task-id>/<input-key>
   ```

   Living under `refs/miniforge/...` keeps it out of the normal
   branch namespace; living under `<run-id>` is per-run-isolated.
   If the ref already exists with the same input-key (replay /
   retry), reuse its tip — the input is byte-identical so the
   output is too.
4. **Stage in a clean temporary worktree.** Worktree path
   `/tmp/miniforge-merge/<task-id>/<input-key>`; cleaned up on
   completion regardless of outcome.
5. **Run the merge** with the §3.1 pinned flags. For 2 effective
   parents: `git merge -s ort`; for 3+: `git merge -s octopus`.
6. **On success**, push the resulting commit to the namespaced ref.
   Drop the temp worktree.
7. **On conflict**, capture
   `{:git/exit-code :git/stderr :merge/conflicts}` and hand to the
   resolution sub-workflow per §6.1. Drop the temp worktree (the
   sub-workflow re-stages from the conflict info, not the residual
   on-disk state).

`task-sub-opts` calls `merge-parent-branches!` for multi-parent tasks
and uses the returned `:merge/ref` (or the bare `:merge/sha` for
single-effective-parent fast path) as the sub-workflow's `:branch`.
Single-parent tasks keep their current code path entirely unchanged.

`execute-plan-as-dag` no longer rejects non-forest plans. v1's forest
validator is dropped from the gate path; `validate-forest` survives
as an informational helper.

### 7.3 Resolution sub-workflow (new component)

Likely lives under `components/merge-resolution/` as a sibling to the
existing phase-software-factory workflow definition, OR as a
specialized invocation of the standard implement / verify pipeline
with a curated agent prompt and the §6.1.2 curator wired in. Either
shape is acceptable; the contract is what §6.1 specifies. Reused by
both the §6.1 pre-task trigger and the §6.4 PR-lifecycle trigger.

### 7.4 `pr-lifecycle` (`components/pr-lifecycle/src/...`)

The PR monitor loop already detects state transitions. Hook the
MERGEABLE→NON_MERGEABLE transition to invoke the resolution sub-
workflow with the two-parent input shape from §6.4. On success, push
the resolution commit and let the lifecycle resume. On
`dag-multi-parent-unresolvable`, surface to the human review path
that already exists for other lifecycle failures.

### 7.5 Plan schema

- New optional `:task/merge-strategy` enum:
  `#{:git-merge :sequential-merge}`.
- **No** `:plan/strict-forest?` flag (dropped per §5).
- `:task/dependencies`: vector strongly preferred for new plans
  (ordered merge behavior matters); set still accepted, sorted
  deterministically per §3.1.

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

- **Round-2 / GPT-5.5 review additions** (test cases that verify the
  Git-semantics decisions from §10.11):

  | Case                                                                  | Expected result                                                            |
  | --------------------------------------------------------------------- | -------------------------------------------------------------------------- |
  | Two-parent diamond                                                    | Uses `git merge -s ort`, not octopus; one merge commit with two parents.   |
  | Three-parent fan-in with disjoint files                               | Uses `git merge -s octopus`; one merge commit with three parents.          |
  | Parent order replay                                                   | Same ordered SHA list across repeated runs of the same plan.               |
  | Branch name moves after resolution but before merge                   | Merge still uses captured `:sha` snapshot.                                 |
  | Duplicate parent tips (same SHA via different declarations)           | Collapses to single-parent fast path; emits `:dag/multi-parent-no-op`.     |
  | Parent A is ancestor of B, A first in dependency vector               | Collapses A into B deterministically; first-parent rule applies post-collapse; no contradiction. |
  | Unrelated histories                                                   | `:anomalies/dag-multi-parent-unrelated-histories`; no `--allow-unrelated-histories` invoked. |
  | GPG signing configured in user config                                 | `--no-gpg-sign` prevents signing; merge succeeds with unsigned commit.     |
  | Pre-commit hook configured to fail                                    | `--no-verify` skips the hook; merge succeeds (orchestrator runs verify downstream). |
  | Existing namespaced ref with same input-key                           | Idempotently reuses the existing merge commit instead of creating a new one. |
  | Existing namespaced ref with same task-id but different parent SHAs   | Different `:merge/input-key` ⇒ different ref ⇒ no collision.               |
  | Octopus refuses on conflict                                           | Triggers resolution sub-workflow with conflict info, NOT a hard failure to a human. |
  | Resolution sub-workflow recurring-conflict (curator)                  | Terminates early with `:resolution/reason :curator/recurring-conflict` instead of burning full budget. |
  | Resolution sub-workflow markers-not-resolved (curator)                | Iteration fails and re-prompts; persistent recurrence escalates to terminal anomaly. |
  | Out-of-band parent fast-forward push (snapshot still reachable)       | `:dag/multi-parent-parent-tip-advanced` log; merge proceeds with snapshot SHA. |
  | Out-of-band parent force-push (snapshot unreachable)                  | `:anomalies/dag-multi-parent-parent-rewritten`; no auto-retry.             |
  | PR-lifecycle MERGEABLE→NON_MERGEABLE with marker-only diff            | Verify gate short-circuits on GitHub's MERGEABLE confirmation per §6.1.1.  |
  | PR-lifecycle MERGEABLE→NON_MERGEABLE with substantive diff            | Verify gate runs full project test pipeline.                               |

---

## 10. Resolved Decisions (audit trail)

§10.1–§10.5 are round-1 outcomes (settled in v0.2). §10.6–§10.10 are
round-2 outcomes (settled in v0.3 from the user's answers + the
GPT-5.5 technical review). The design sections above already reflect
all answers; this section preserves the reasoning trail.

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

### 10.6 Resolution budget — "prevent exhaustion from stagnation"

- **User direction:** "We need a default, but I really want that to be
  set from 'prevent exhaustion'. Most token exhaustion is caused by
  repetitive loops that have no progress. Work that gets done is not
  something we want to reduce using a hammer. We want to have telemetry
  that allows us accounting to extract places to optimize (performance
  monitoring)."
- **Decision:** budget shape is `{:max-iterations N :stagnation-cap M}`,
  not a token cap. The `:stagnation-cap` is the actionable lever — it
  ends loops that aren't making progress, which is where waste actually
  comes from. `:max-iterations` is a backstop. Tokens are tracked as
  telemetry for performance optimization, NOT used to terminate work
  that's still progressing.
- **Defaults to start at:** `{:max-iterations 5 :stagnation-cap 2}` —
  five iterations is enough headroom for genuine resolution work; two
  consecutive stagnant iterations (caught by §6.1.2 curator's
  `:curator/recurring-conflict`) is the early-out for stuck loops.
  Numbers re-tunable based on dogfood telemetry.
- **Telemetry:** every resolution iteration emits
  `:resolution/iteration-completed` with iteration index, conflict-
  set delta from prior iteration, tokens consumed, wall-clock. The
  dashboard reads these to surface "merges that needed N iterations"
  / "merges that hit stagnation" — that's the optimization signal,
  not the limit.

### 10.7 Verify gate runs the standard pipeline

- **User direction:** "That depends on the policy. For miniforge local,
  we have pre-commit that will run those and other hard gates. If the
  PR monitor loop makes changes re: comment it moves through
  verification. If we are doing merge, then [...]." (Sentence trails
  off; the rule the user sketched is consistent: verification follows
  the same policy the parent task's verify phase uses.)
- **Decision:** the resolution sub-workflow's verify gate runs the same
  pipeline as the parent task — project tests + policy-enforced hard
  gates (in miniforge-local, the pre-commit chain). For §6.4 PR-
  lifecycle resolution the verify gate can short-circuit on GitHub-
  reports-MERGEABLE if the resolution diff is purely conflict-marker
  deletions; any substantive change reaches the full pipeline.
- **Documented in §6.1.1** as the verify-gate-policy rule.

### 10.8 Curator detects recurring / failed merges

- **User direction:** "We should be able to detect that merge conflicts
  have recurred, but a failed merge should fail. If that needs a
  curator we should add one, it is a failure point. Agent might give up
  on a rebase or merge strategy or exhaust budgets."
- **Decision:** add a merge-resolution curator with two checks
  (`:curator/markers-not-resolved` and `:curator/recurring-conflict`)
  per §6.1.2. The recurring-conflict check is the "detect that merge
  conflicts have recurred" lever the user named — when iteration N's
  conflict set matches iteration N-1's, the curator escalates rather
  than letting the agent burn the rest of the budget. A failed merge
  surfaces as the §6.1 terminal anomaly with a `:resolution/reason`
  that names which curator condition triggered.

### 10.9 Hierarchical event tagging (§3.3)

- **User direction:** "Namespaced event tags, with `<parent>.<sub>.<task>`
  type taxonomy. Not exactly that likely, but something similar. That
  or use analog of the twitter snowflake algo where the spec is the
  'data center', parents the server, etc. Would produce sortable
  dependency ordering on tags."
- **Decision:** events carry an `:event/path` of
  `[<run-id> <plan-id> <task-id> <resolution-id-or-nil>]` per §3.3.
  Snowflake-style ordered IDs are an acceptable implementation
  technique; the contract is the dashboard-sortable hierarchical key,
  not a specific encoding.

### 10.10 Out-of-band parent tip changes — refresh or anomaly per consequence (§6.6)

- **User direction:** "If it leaves us in a non deterministic state, or
  in violation of policy, would be an anomaly. If it is simple to
  handle the detect and refresh."
- **Decision:** §6.6 implements exactly that split. Snapshotted SHA
  still reachable (parent did a normal advance) → log and proceed
  with the snapshotted SHA. Snapshotted SHA unreachable (parent was
  rewritten) → typed anomaly, surface to a human. The orchestrator
  does not silently re-snapshot; that would let upstream drift quietly
  mutate the merge inputs, which is the same class of footgun v0 had.

### 10.11 GPT-5.5 review — Git semantics tightening

- **Source:** technical review of v0.2 catching real Git issues.
- **Decisions** (all reflected in v0.3 above):
  - **Strategy rename `:octopus` → `:git-merge`** — git uses `ort` for
    two-head merge and `octopus` only for 3+. Calling the user-facing
    default "octopus" was misleading for the most common (two-parent)
    case. The internal sub-mode is selected by parent count.
  - **`:sequential-cherry-pick` removed**, `:sequential-merge` added —
    cherry-pick over parent branches that contain merge commits is
    unsafe without `--mainline` plumbing and silently drops merge-
    resolution edits. Sequential merge preserves history correctly.
  - **Determinism reframed** — same effective merge result (tree +
    parent ordering) modulo Git version/config. SHA-equality across
    runs is not promised; it would require pinning author, committer,
    timestamps, message bytes, signing config, and hooks. Pinned what
    we CAN pin: `--no-edit --no-gpg-sign --no-verify --no-ff -m
    <generated>`.
  - **Parents resolved to SHAs at resolution time** — branch names are
    mutable; SHAs are not. Registry shape carries `:sha`, `:order`,
    and `:merge/input-key` (idempotency hash).
  - **Parent collapse precedence pinned** — collapse duplicates and
    ancestors first, preserve plan order on remaining tips, then
    assign first-parent. Resolves the §3.1 / §6.2 contradiction in
    v0.2.
  - **§6.3 renamed** "empty parent intersection" → "duplicate /
    identical parent tips". The prior name suggested unrelated
    histories, which is a different (and dangerous) condition.
  - **§6.5 added — unrelated histories anomaly.** Git refuses these
    by default; v2 does NOT use `--allow-unrelated-histories`.
  - **§6.1 conflict shape adjusted** — conflict entries carry
    `:parent-candidates` and `:attribution`, not "exact pair";
    octopus output doesn't always give us a guaranteed minimal
    pair. Added `:git/exit-code` and `:git/stderr` for raw
    diagnostics.
  - **Namespaced ref name** —
    `refs/miniforge/dag-base/<run-id>/<task-id>/<input-key>`.
    Replays of the same effective input reuse the same ref instead
    of accumulating new ones.

---

## 11. Open Questions for Iteration Round 3

Round 1's five questions and Round 2's five questions are all settled
(§10.1–§10.10) plus the GPT-5.5 review issues (§10.11). These are the
remaining design questions surfaced during the v0.3 pass:

1. **Curator integration shape.** The §6.1.2 curator wraps the
   resolution agent's iteration loop. Is it a new component (a
   sibling to `phase-software-factory`'s implementer curator) or an
   extension of the existing curator multimethod? Lean extension —
   the merge-resolution checks (`:curator/markers-not-resolved`,
   `:curator/recurring-conflict`) compose naturally with the existing
   `:curator/no-files-written` check. Default-on for the resolution
   sub-workflow only; off for the regular implement loop where it
   would be noise.

2. **Resolution agent prompt scaffolding.** The agent gets the
   conflict info, the parent SHAs, the strategy. What prompt-template
   gives the best resolution rate? Open question for the
   implementation phase — start with a literal "here are the
   conflicts, resolve them and explain the resolution" prompt, tune
   based on iteration-count telemetry from §10.6.

3. **Concurrent resolution sub-workflows.** Two sibling tasks at the
   same stratum could both hit conflicts and trigger resolution
   sub-workflows simultaneously. Each gets its own ephemeral worktree
   (different paths) and its own resolution agent (different sub-
   workflow ids), so they don't fight. But they may compete for LLM
   rate-limit budget. Worth a max-concurrent cap on the resolution
   pool? Defer to dogfood telemetry.

4. **Resolution success — should we also push the resolution to a
   persistent ref?** §7.2's namespaced ref already lives at
   `refs/miniforge/dag-base/<run-id>/<task-id>/<input-key>`, which is
   per-run. If a future replay of the same run hits the same input-
   key, it reuses. But cross-run replay (e.g., re-running an entire
   workflow from scratch with the same plan) gets a different
   `<run-id>` and re-resolves from scratch. Acceptable for v2 (the
   resolution work isn't free but isn't catastrophic), but worth
   considering whether the input-key alone (without `<run-id>`) is a
   better cache key.

5. **Resolution-sub-workflow telemetry attribution to the parent
   task's cost report.** The parent task's metrics include implement
   tokens, verify wall-clock, etc. Resolution-sub-workflow tokens
   should roll up into the parent task's totals (the merge IS work
   the parent task incurred), but ALSO be visible separately so the
   dashboard can answer "where is the cost coming from in this run?"
   Likely a `:cost/breakdown {:task/implement N :task/verify M
   :task/merge-resolution K}` shape. Worth specifying explicitly?

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

v2 closes that gap by performing a deterministic git merge of
multi-parent task bases at orchestration time — `ort` for the common
two-parent fan-in, `octopus` for 3+ parents, `:sequential-merge` as
an alternate per-task strategy. When the merge conflicts, an
automated resolution sub-workflow takes the parents and conflicts as
input and produces a clean merge commit, gated by a merge-resolution
curator that detects stagnant loops; only when the sub-workflow
itself exhausts its budget does anything surface to a human. The
same sub-workflow handles mid-PR-lifecycle MERGEABLE→NON_MERGEABLE
transitions, so the release flow is closed end-to-end without HIL
touch points in the common case.

The work this spec covers, roughly:

1. Extend the plan schema with `:task/merge-strategy`
   (`#{:git-merge :sequential-merge}`).
2. Replace `resolve-base-branch`'s multi-parent anomaly with
   `resolve-multi-parent-base` returning SHA-pinned parent tips +
   collapse normalization + `:merge/input-key` per §3.2; drop the
   v1 forest gate.
3. Implement `merge-parent-branches!` in the orchestrator per §7.2
   (clean temp worktree, namespaced ref naming, pinned merge flags
   from §3.1, criss-cross fallback to chained recursive).
4. Build the merge-conflict resolution sub-workflow (§6.1 contract)
   with the §6.1.2 curator wired in (both `:curator/markers-not-resolved`
   and `:curator/recurring-conflict` checks).
5. Wire `task-sub-opts` to call `merge-parent-branches!` for
   multi-parent tasks.
6. Hook `pr-lifecycle`'s monitor loop on MERGEABLE→NON_MERGEABLE to
   invoke the resolution sub-workflow per §6.4.
7. Hierarchical event tagging per §3.3 across all v2 events.
8. Tests (§9 plus the GPT-5.5 review additions) + a re-run of the
   2026-05-03 dogfood against `I-CLASSIFICATION-RUNTIME.md` to
   prove the gap closes.
