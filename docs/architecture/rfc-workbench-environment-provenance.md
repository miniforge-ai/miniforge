<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# RFC: Environment provenance at the workbench boundary

**Status:** Proposed (2026-08-06). Written against `workbench-contract`
at `3196b5f` and the two miniforge product adapters as built. The three
questions it opened were resolved from source on 2026-08-07 — see
"Questions resolved from source"; nothing is left pending for a reader
to answer.

A minibench experiment is only readable if its arms differ along the
declared axes and nothing else. The workbench contract pins the
*workload* an arm ran — `source_hashes`, and the ETL adapter fails
closed when a candidate's hash does not match its baseline's. It does
not pin the *environment* the arm ran in. That is whatever the operator
set up by hand, and no consumer of a snapshot can tell a clean arm from
a contaminated one.

This RFC closes that gap at the same boundary the contract already
defends.

## Decisions to ratify

1. **`RunEnvironment` becomes a field on `WorkbenchSnapshot`** — an arm
   records where it ran and what it shared.
2. **The adapter computes `isolated`; it does not accept it.** Isolation
   asserted by the runner is worth nothing. A runner that could be
   trusted to be isolated would not have produced the incident below.
3. **A non-isolated arm fails the resolved-run boundary.** It is a gate,
   not a warning field.
4. **Provisioning stays out of the adapters.** The adapter gates;
   `bb bench:provision` provisions. See "Rejected alternatives".

## The evidence

Three findings from the 2026-08-06 seeded-trap bench.

**The sandbox was a linked worktree of the live checkout.**

```text
$ git -C ~/.miniforge/bench/repo rev-parse --git-dir --git-common-dir
/Users/chris/ws/miniforge.ai/miniforge/.git/worktrees/repo
/Users/chris/ws/miniforge.ai/miniforge/.git
```

A linked worktree owns HEAD, the index, and the working tree; config and
every other ref live in the shared common dir. So the bench's deliberate
origin redirect — intended to stop a completed run opening a real PR —
rewrote the launching checkout's `remote.origin.url`, and its
`git fetch origin main` force-updated the launching checkout's
`refs/remotes/origin/main` backwards to the mirror's stale head. Fixed
generically in [PR #1685](https://github.com/miniforge-ai/miniforge/pull/1685).

**Both arms shared task-worktree storage.** The bench runner records it
as a known condition and works around it:

> Task worktrees pool here regardless of `MINIFORGE_HOME`
> (verified 2026-08-06) — attribute per run by before/after diff.

An attribution heuristic standing in for isolation.

**Both arms shared `refs/heads/task-*` with the live checkout**, for the
same reason the config leaked.

The first is git hygiene. The second and third are validity threats: the
arms were not independent, and nothing in the resulting snapshots says
so. The completed baseline matrix reported a NULL primary. A null from
arms that shared state is not the same evidence as a null from arms that
did not, and today's contract cannot distinguish them.

## Design

### The contract addition

```clojure
(def RunEnvironment
  [:map {:closed false}
   [:kind :string]                                ;; clone | worktree | container | unknown
   [:isolated :boolean]
   [:repo_digest {:optional true} :string]        ;; commit the arm ran at
   [:state_root_digest {:optional true} :string]  ;; digest of the arm's MINIFORGE_HOME
   [:shares_with {:optional true} [:vector :string]]])
```

and on `WorkbenchSnapshot`:

```clojure
[:environment {:optional true} RunEnvironment]
```

Optional in the schema because every existing snapshot lacks it, and
every map in the contract is `{:closed false}`, so the addition is
non-breaking. Optional in the schema and required by the gate are
different layers, deliberately — see "Adoption order".

Digests rather than paths. A path is a local fact and leaks the
operator's filesystem into an artifact meant to be compared across
machines. The only question asked of a state root is *same or
not-same*, which a digest answers without the path travelling.

### Attestation at provision time, re-verification at projection time

The facts are knowable when the sandbox is built and may be gone by the
time the run resolves — task worktrees are cleaned up after a run, which
is why the bench runner falls back to the surviving task branch. So:

- `bb bench:provision` writes `<state-root>/environment.edn`, recording
  `kind` plus the git-dir / common-dir facts `bench/isolation-report`
  already computes.
- The adapter reads it at projection and re-verifies whatever is still
  observable. An attestation on its own is a claim; re-verification is
  what catches drift — a remote that was repointed mid-experiment, say.
- A missing attestation yields `kind "unknown"` and `isolated false`.
  Absence is not innocence.

### The gate

`project` in both adapters is already a `cond` of fail-closed guards —
missing run, unresolved run, missing baseline factors, source-hash
mismatch, invalid snapshot. This adds one clause in the same shape, on
the same channel:

```clojure
(not (:isolated environment))
(schema/failure :snapshot unisolated-environment-message)
```

No new failure mode, no new boundary. The environment is held to the
same bar as the workload hash beside it.

### Cross-arm independence

`isolated` is per-arm: did this arm share state with anything. The
question an experiment actually asks is whether the arms shared state
*with each other*, which is a comparison across snapshots and so belongs
where snapshots are already compared — the baseline-versus-candidate
check in the ETL adapter, which today compares `source_hashes`.

Extend it to `state_root_digest`: equal digests across two arms means
they shared a state root, and the candidate fails against that baseline.

This is the clause that closes the 2026-08-06 validity hole, and it
falls out of the existing baseline seam rather than needing a new one.

## What this does not do

- It does not make the worktree executor isolate config and refs. That
  is a separate defect, in flight.
- It does not detect contamination through channels outside git and the
  state root — a shared LLM cache, a shared checkpoint directory,
  ordering effects between interleaved arms. `isolated` means isolated
  along the axes that are observable here, and the field name should not
  oversell that; `git_isolated` is the honest name if the ambiguity
  bites in practice.
- It does not repair completed benches. It invalidates them. That is the
  intent, and it is why the gate ships as its own release.

## Rejected alternatives

**The adapter provisions the sandbox.** The adapter's whole contract is
defined at the resolved-run boundary, after a run reaches terminal
status; provisioning happens before. One component with both lifecycles
gets two failure modes and two sets of callers, and the projection code
would have to hold environment state across the entire run. Gate, do not
provision.

**A warning field rather than a gate.** A warning nothing consumes is a
warning nobody reads. The adapters already fail closed on unresolved
runs and source-hash mismatches; holding the environment to a lower bar
than the workload hash sitting next to it would be inconsistent. The
incident is itself the evidence that the softer discipline — a paragraph
in a runsheet — does not hold.

**Record raw paths.** Leaks operator filesystem layout into a shared
artifact and makes snapshots non-comparable across machines.

**Put it in `:metadata`.** That field is `any?`. Nothing can gate on an
unschematized blob, and the point of this is to be gateable.

## Adoption order

1. `RunEnvironment` and the snapshot field in `workbench-contract` — the
   Rust type plus the Clojure binding — released as a tagged version.
   Nothing consumes it yet; purely additive. (There is no Swift binding
   to update: `bindings/swift/` holds a README and nothing else.)
2. `bb bench:provision` writes the attestation. Reuses
   `bench/isolation-report`, already shipped in PR #1685.
3. Both adapters read, re-verify, and populate `:environment`. Still no
   gate — snapshots start carrying the field so the first gated release
   has data to gate on.
4. Turn the gate on.
5. Extend the baseline comparison to `state_root_digest`.

Steps 1-3 are non-breaking. Step 4 is the breaking one and should land
alone, so the release that invalidates existing benches is the only
thing in it.

## Questions resolved from source

Three questions were open when this RFC was first written. All three are
answerable from the code, and were.

### Container runs are isolated, but not trivially

`clone-and-checkout!` (`dag-executor/executor.clj:330`) runs
`git clone <repo-url> .` *inside* the environment via `execute!`. On the
OCI executor that is inside the container, so the container's repository
is a fresh clone with its own `.git` — its config and refs are its own.

The qualifier is host mounts: a container whose workdir is bind-mounted
from the host is back to sharing. That question is already answered by
existing code. `plan-security/scan-plan` reports a host runtime-socket
mount as `:hard-stop` and any host mount outside the allowlist as
`:review`. So `kind "container"` yields `isolated true` when the workdir
is not covered by a host mount, and the plan-security scan is what
decides it. No new mechanism, and step 3's adapter work reads a verdict
that already exists.

An incidental defect surfaced while answering this, and belongs in its
own issue rather than here: `infer-repo-url`
(`cli/workflow_runner/sandbox.clj:63`) falls back to the launching
directory's `git remote get-url origin` and hands that to
`clone-and-checkout!` to execute *inside* the container. When origin is a
local path — precisely what the 2026-08-06 incident produced — the
container is told to clone a host path that does not exist inside it.

### Overrides follow the house pattern, and go one better

The pattern already exists twice: `MINIFORGE_COMMIT_BUDGET_OVERRIDE`
(`tasks/commit_budget.clj:125`) and `MINIFORGE_PR_BUDGET_OVERRIDE`
(`tasks/pr_budget.clj:60`). Both allow the override, both require a
rationale string, and both echo that rationale so it lands in the log or
the PR trail. That is the same discipline the bench runsheet states for
itself — amendments after the first counted run are logged, not edited.

So: overridable, by that pattern, with one improvement. The two
precedents put the rationale in a log, which is separable from the thing
it excuses. This one writes itself into the snapshot:

```clojure
[:override {:optional true} [:map {:closed false}
                             [:rationale :string]
                             [:at :string]]]
```

A snapshot admitted over a failed environment check carries the reason
for the rest of its life, and any comparison that includes it can see
that. Costs nothing, and it is strictly harder to lose than a log line.

### Nothing in Thesium breaks

Verified rather than assumed, on four points:

- **Rust.** No `deny_unknown_fields` anywhere in `crates/`. Optional
  fields already use `Option` with `skip_serializing_if`. Adding one more
  is compatible in both directions.
- **Swift.** There is no Swift binding — `bindings/swift/` holds a README
  and nothing else. Nothing to break.
- **Clojure consumers.** All Thesium consumption goes through
  `workbench-adapter-kit`, which validates with `bv/valid?` and
  `bv/validate!` against the contract's own `schema/WorkbenchSnapshot`.
  No consumer re-declares the map and none sets `:closed true`.
- **Signatures.** Nothing in-tree verifies a snapshot signature, so an
  added field cannot invalidate a digest check that does not exist.
