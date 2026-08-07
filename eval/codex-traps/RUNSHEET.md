<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Codex seeded-trap bench — runsheet (T2 §5.1-5.3)

DRAFT pre-registration 2026-08-06. Becomes binding at the first counted
run; amendments after that are logged, not edited. Companion to
eval/codex-baseline/RUNSHEET.md (the observational arm — completed
2026-08-06 with a NULL primary and an all-:uncovered ledger; THIS bench
answers the question that matrix could not: when the codex DOES cover
the failure mode, does delivery change the outcome?).

## Design

1. Traps are manufactured specs (T1 §5.2.3 origin: manufactured) whose
   natural-mistake failure mode is a problem the codex ALREADY lists
   with strong anchoring. One trap per spec; the task is real and
   completable; the trap is the tempting shortcut.
2. Arms: baseline (MINIFORGE_CODEX_PATH unset) vs treated (set), same
   pinned models as the baseline runsheet, interleaved B,T per rep,
   N=3 reps per trap per arm — 18 runs total. Fresh state per run;
   spec masters live in eval/codex-traps/specs/ and are re-copied
   before every run (lesson: the runner moves failed specs to
   work/failed/ and reset cannot restore untracked copies).
3. Trap catch is judged MECHANICALLY by a per-trap detector script run
   against the run's produced diff/worktree after termination —
   detectors are written and frozen BEFORE the first counted run.
   Detector outcomes: :caught (trap avoided), :sprung (failure mode
   present), :not-reached (run died before the trap site). :not-reached
   is excluded from the catch-rate denominator and reported beside it.

## Traps (anchor → task sketch → detector)

1. TRAP-A contract-drift: problem contract-drift-is-silent, situation
   changing-one-side-of-a-boundary, scars knight-capital-flag-reuse +
   round-format-drift. Spec: rename/reshape a producer-side datum with
   a small but real consumer web (final target chosen at spec
   authoring; candidate: an event key with consumers across component +
   task + doc). Detector: grep the produced diff + worktree for stale
   old-form references at consumer sites.
2. TRAP-B pipeline-signal-loss: problem test-signal-integrity, scars
   gh-actions-omits-pipefail + ci-masked-red-twice. Spec: adjust a CI
   or script step ("capture the output of X to a log file") where the
   natural edit is `cmd | tee` without pipefail, silently turning the
   step green. Detector: static check of the produced script/workflow
   for unprotected pipes / lost exit-code propagation.
3. TRAP-C check-act-atomicity: problem check-act-atomicity, situation
   multiple-writers-one-target. Spec: add a create-if-absent-then-write
   feature (registry/cache file). Detector: static check for
   exists?-then-write with no lock/atomic-rename; anything ambiguous
   lands in a review queue, not the ledger (T2 §3.4 discipline).

## Pre-registered endpoints

PRIMARY: trap catch rate per arm (detector :caught / (:caught +
:sprung)), per trap and pooled.

SECONDARY: :not-reached count (bench validity signal), review blocking
issues per run, terminal status, redirects, tokens + cost + duration,
and — treated arm — consultation status on the phase records plus
gap-ledger bucket counts.

READING DISCIPLINE (T2 §5.3): a null here with delivery evidence
present indicts the phase→situation mapping or the landing's
actionability, NOT codex content; the report must say which was tested.
Small N gives direction, not significance.

## Status

1. 2026-08-06: design drafted. NOT yet binding — spec authoring,
   detector freeze, and shakeout (one uncounted run per trap spec to
   confirm the task completes and the trap site is reachable) come
   first, in that order.

## Run log

(one line per run, appended by the runner wrapper)

## Concretized 2026-08-06 (pre-shakeout; binding at first counted run)

1. Pin: bade0222fa61669a96c31a7e99a87ccbfcdf56d5 (same as baseline
   matrix chunks 2-3).
2. Trap targets (from code survey): TRAP-A = read-ledger result key
   :skipped -> :torn-lines; the silent consumer is bb.edn's
   codex-gap-report task body (never executed by CI's load-only
   classpath check). TRAP-B = pr-size.yml 'Check PR size budget' step
   (pure exit-code gate, no pipefail anywhere in the file). TRAP-C =
   ensure-fleet-config! in pr-sync; correct idiom (with-config-lock!)
   sits in the same namespace at core.clj:142-198.
3. Detectors frozen in detect.bb; self-tested :not-reached x3 against
   the pristine pin.
4. Origin redirect: the bench repo's origin now points at the local
   bare mirror ~/.miniforge/bench/origin.git so completed runs cannot
   open real PRs; the release phase's PR step is expected to fail
   after push, which is AFTER every trap site — trap detection reads
   the task worktree, unaffected. Terminal-status comparisons therefore
   exclude the release phase.
   SUPERSEDED by AMENDMENT 2026-08-06 (b): the redirect procedure this
   item describes rewrote the launching checkout. Its consequence for
   the release phase is unchanged and still holds.
5. Runner: run-trap.bb (re-copies spec master; detects over the run's
   new ~/.miniforge/worktrees/task-* dirs — pooled, attributed by
   before/after diff; strongest verdict recorded).
6. SHAKEOUT (uncounted, codex OFF): one run per trap to confirm the
   task completes and the trap site is reachable. Counted matrix only
   after all three shake out.

## AMENDMENT 2026-08-06 (b) — sandbox provisioning; harness into the repo

Logged before the first counted run. No counted run has executed; the
only rows in `runs.edn` are the uncounted trap-b and trap-a shakeouts,
both baseline arm, both produced under the defective sandbox below.

1. SANDBOX DEFECT. The bench repo was a linked `git worktree` of the
   live checkout. A linked worktree owns HEAD, the index and the working
   tree, while `.git/config` and every other ref live in the shared
   common dir. So step 4's `git remote set-url origin <mirror>` rewrote
   the LIVE checkout's origin, and the bench's `git fetch origin main`
   force-updated the LIVE checkout's `refs/remotes/origin/main` backwards
   to the mirror's stale head. Both silent. Fixed generically in
   miniforge PR #1685; see `DOGFOODING.md` §Bench Runs.

2. PROVISIONING (replaces step 4's redirect). Never `git worktree add`:

   ```bash
   bb bench:provision /path/to/launching/checkout <pin-sha> main
   ```

   This clones to `~/.miniforge/bench/repo`, inits the bare mirror
   `~/.miniforge/bench/origin.git`, pushes the pin to it, and redirects
   only the clone's origin. It re-reads the launching checkout's
   `remote.origin.url` afterwards and fails the provision if it moved.
   `MINIFORGE_BENCH_ROOT` overrides the root.

3. RUNNER GATE. `run-trap.bb` refuses to start unless both
   `bb bench:verify <repo> [$MINIFORGE_BENCH_SOURCE]` exits 0 and the
   sandbox's `origin` is the bare mirror beside it. The second condition
   exists because the harness is now tracked: without it, running
   `run-trap.bb` from an ordinary checkout would pass the first and then
   `git reset --hard` that checkout. A refusal exits 2 and touches
   nothing. Anything the guard cannot determine is a refusal.

4. PIN AND `MINIFORGE_BENCH_SOURCE`. The gate shells to `bb bench:verify`,
   which does not exist at the pre-registered pin `bade0222fa6`. Set
   `MINIFORGE_BENCH_SOURCE` to the launching checkout and the gate runs
   the task from there instead of from the sandbox, so the pin stays as
   pre-registered. Unset, the gate refuses on that pin. The pin itself is
   unchanged.

5. CODEX PATH. `run-trap.bb` carried a hardcoded personal path. It reads
   `MINIFORGE_CODEX_PATH` with no default, and the treated arm refuses to
   start when it is unset or is not a directory — an absent codex would
   have run a second baseline under a treated label.

6. HARNESS COMMITTED. `eval/codex-traps/` was untracked and existed only
   inside the bench. `RUNSHEET.md`, `run-trap.bb`, `detect.bb` and
   `specs/` are now tracked in miniforge; `runs.edn` is gitignored, since
   it is appended experiment output rather than instrument. `detect.bb`
   gained the license header, an `ns` form, layer headings and stratum
   metadata; every detector expression is byte-identical to the frozen
   version, and the pristine-tree self-test still reads `:not-reached`
   three times.
