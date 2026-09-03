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
   detector freeze, and a test run (one uncounted run per trap spec to
   confirm the task completes and the trap site is reachable) come
   first, in that order.

## Run log

(one line per run, appended by the runner wrapper)

## Concretized 2026-08-06 (pre-test-run; binding at first counted run)

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
6. TEST RUNS (uncounted, codex OFF): one run per trap to confirm the
   task completes and the trap site is reachable. Counted matrix only
   after all three test runs pass.

## AMENDMENT 2026-08-06 (b) — sandbox provisioning; harness into the repo

Logged before the first counted run. No counted run has executed; the
only rows in `runs.edn` are the uncounted trap-b and trap-a test runs,
both baseline arm, both produced under the defective sandbox below.

1. SANDBOX DEFECT. The bench repo was a linked `git worktree` of the
   live checkout, which shares `.git/config` and every ref. Step 4's
   `remote set-url` therefore rewrote the LIVE checkout's origin and the
   bench's `fetch` force-updated its `refs/remotes/origin/main`
   backwards, both silently. Mechanism and fix: miniforge PR #1685 and
   `DOGFOODING.md` §Bench Runs.

2. PROVISIONING replaces step 4's redirect — never `git worktree add`:

   ``bash
   bb bench:provision /path/to/launching/checkout <pin-sha> main
   ``

   Clones to `<root>/repo`, inits the bare mirror `<root>/origin.git`,
   pushes the pin to it, and redirects only the clone's origin, failing
   if the launching checkout's `remote.origin.url` moved.
   `MINIFORGE_BENCH_ROOT` overrides the root.

3. RUNNER GATE. `run-trap.bb` refuses unless both
   `bb bench:verify <repo> [$MINIFORGE_BENCH_SOURCE]` exits 0 and the
   sandbox's `origin` is the bare mirror beside it. The second exists
   because the harness is now tracked: without it, running from an
   ordinary checkout passes the first and then resets that checkout.
   A refusal exits 2, touches nothing, and is what any undeterminable
   state produces.

4. PIN AND `MINIFORGE_BENCH_SOURCE`. `bb bench:verify` does not exist at
   the pre-registered pin `bade0222fa6`. Set `MINIFORGE_BENCH_SOURCE` to
   the launching checkout and the gate runs the task from there, so the
   pin stands unchanged; unset, the gate refuses on that pin.

5. CODEX PATH. `run-trap.bb` carried a hardcoded personal path. It now
   reads `MINIFORGE_CODEX_PATH`, no default, and the treated arm refuses
   when it is unset or not a directory — an absent codex would have run
   a second baseline under a treated label.

6. DETECTOR OUTCOMES gain `:detector-error` — a detector that exits
   non-zero, says nothing, or prints unreadable output. It used to throw
   and take the whole record of an hours-long run with it. It outranks
   nothing, so it is a run's verdict only when no real one exists, and
   it leaves the catch-rate denominator like `:not-reached` does.
   `:sprung` also stops tying `:caught`: mixed evidence records the
   sprung trap, not whichever worktree came first.

7. ARM STATE moves to `<sandbox-root>/home/<arm>` from a fixed
   `~/.miniforge/bench/home/<arm>`. Identical for the default sandbox,
   so the pre-registration stands; for any other, the arms stop sharing
   an event stream with the default one, which is what makes per-run
   attribution by events/live diff sound.

8. STRAY EVENTS, logged for the auditor. On 2026-08-07 a gate test from
   a throwaway sandbox reached `bb dogfood` and wrote workflow ids
   `1bf1238a-7834-46a4-9f71-8c1f3e4f5a00` and
   `9401431b-2414-4131-9eb6-3c1e127a5768` into
   `~/.miniforge/bench/home/baseline/events/live` with no `runs.edn` row.
   It died in 13s on a classpath error, made no model calls, and touched
   neither `~/.miniforge/bench/repo` nor the mirror. Left in place: they
   sit in every future run's "before" set. Item 7 stops it recurring.

9. HARNESS COMMITTED. `RUNSHEET.md`, `run-trap.bb`, `detect.bb` and
   `specs/` are tracked in miniforge now; `runs.edn` is gitignored as
   appended output rather than instrument. `detect.bb` gained the
   license header, an `ns` form, layer headings and stratum metadata,
   with every detector expression byte-identical to the frozen version
   and the pristine-tree self-test still reading `:not-reached` x3.

## AMENDMENT 2026-08-10 — restarts mid-matrix, scheduling only

The counted matrix launcher died three times with its operator session
(two session exits, one machine reboot) and once more when every slot
began failing at bb startup. Root cause of the startup failures,
initially misread twice: the volume holding the launching checkout
(/Volumes/Work) was intermittently failing getcwd() with EINTR, which
presented first as "cannot run bb" (misread as a lost PATH), then as
GraalVM isolate-init deaths (misread as the source worktree being
deleted), and also poisoned the stat readings behind the interim
"sandbox replaced by a concurrent actor" hypothesis — that claim is
WITHDRAWN as unverifiable; the observations that produced it came from
the faulting volume. Fix: `MINIFORGE_BENCH_SOURCE` moved to a shallow
clone on the home volume (`~/.miniforge/bench-source`), taking the
bench's last dependency off the faulting volume. Refused/failed slots
wrote no rows; every recorded row survived byte-identical across all
restarts. trap-a's strict B,T alternation is broken at one restart
seam (B1 ran a day before T1); arms alternate within every other trap.
Scheduling deviation only; endpoints and detectors unchanged.

## RESULTS 2026-08-11 — counted matrix complete (18/18 slots resolved)

SUPERSEDED by AMENDMENT 2026-08-11 (b) below: 7 of the 8 :not-reached
verdicts were detection failures, recovered post-hoc. Kept verbatim for
the audit trail; read the REVISED RESULTS instead.

Catch rate = :caught / (:caught + :sprung); :not-reached excluded and
reported beside it (pre-registered). N=3 reps per trap per arm.

1. TRAP-A contract-drift: baseline 0/3, treated 0/2 (1 not-reached).
   Five of five reached runs renamed the producer, updated component +
   tests, and missed the untested bb.edn consumer — identically, both
   arms, plus both uncounted test runs. The strongest and most
   uncomfortable result: the codex COVERS this failure mode
   (contract-drift-is-silent, changing-one-side-of-a-boundary, two
   scars) and delivery is verified working, yet the landing changed
   nothing. Per §5.3 discipline this indicts the landing's
   actionability / phase→situation mapping, not coverage: the warning
   says "grep the whole repo for consumers" as a worry, not as a step
   the implementer's loop must execute before submitting.
2. TRAP-B pipeline-signal-loss: baseline 2/2, treated 2/2 (1
   not-reached each). Ceiling effect — this model adds pipefail
   unprompted. No discriminating power at this tier; keep for weaker
   execution models.
3. TRAP-C check-act-atomicity: baseline 0/0 (three not-reached),
   treated 1/1 (two not-reached). The one reached treated run reused
   with-config-lock! — the codex-pointed idiom. Reachability was the
   dominant failure: 5/6 runs never implemented the function
   (workflow deaths upstream). Denominators too thin to compare arms.
4. POOLED: baseline 2/5, treated 3/5. Direction only; N is small and
   trap-c's baseline denominator is empty.
5. NOT-REACHED: 8/18 slots — the bench's own validity ceiling. The
   dominant loss is workflow mortality (verify/redirect deaths before
   the trap site), the same mortality the observational matrix
   measured. Bench improvement before any rerun: hardier specs or a
   phase-level trap placement, else half the budget buys no verdicts.
6. AGENDA (T2 §1.3 — the number ships with its work list):
   a. Trap-a's miss is now the best-evidenced item in the program:
      landings must become actionable steps at the phase that acts
      (implementer prompt: consumer-web grep before submit), not
      prose worries at plan. Feeds the §7.7/§4.4 telemetry: these
      would classify :unheeded, the bucket the ideal-vs-actual
      argument turns on.
   b. Trap-b retires from this model tier (§4.4 vaccination caveat
      noted: retirement here is bench-trap retirement, not peg
      retirement — the peg guards weaker models).
   c. Trap-c needs reachability before it needs interpretation.

## AMENDMENT 2026-08-11 (b) — detection salvage; revised results

DETECTION DEFECT. run-trap.bb detected over surviving task-*branches,
but the runner deletes a task's branch when its sub-workflow completes
cleanly — so precisely the runs that DID the work were scanned as
absent. Cross-checking every :not-reached verdict against the durable
implement checkpoints (:code/file-paths on the lightweight artifact)
showed 7 of 8 had touched their trap files. Their trees were recovered
from the runner's periodic stash snapshots ("WIP on task-*" dangling
commits, one per ~15min, matched to run windows by committer date;
latest in-window snapshot per run) and the FROZEN detector re-run
unchanged on each. trap-c baseline rep1 is confirmed genuinely
not-reached (no snapshot, no trap-relevant paths in its artifact).
Future runs: detection must read the checkpoint/snapshot record, not
surviving refs; the salvage method above is the specification.

REVISED RESULTS (frozen detector, recovered trees):

1. TRAP-A contract-drift: baseline 0/3, treated 0/3 — SIX of six
   counted runs sprung identically (plus both uncounted test runs:
   eight of eight). Every run renamed the producer, updated component
   and tests, missed the untested bb.edn consumer. Coverage present,
   delivery verified, outcome unchanged: the landing's actionability is
   the indicted rung, now on the strongest evidence in the program.
2. TRAP-B pipeline-signal-loss: baseline 3/3, treated 3/3. Full
   ceiling; the model adds pipefail unprompted. Retired at this tier.
3. TRAP-C check-act-atomicity: baseline 2/2 (1 genuine not-reached),
   treated 3/3. Ceiling on the reached denominator — the baseline also
   finds with-config-lock! unaided; the earlier "codex-pointed idiom"
   reading is withdrawn.
4. POOLED: baseline 5/8, treated 6/9. No treated advantage anywhere in
   the matrix.
5. REACHABILITY: 17/18 — the earlier "workflow mortality / hardier
   specs" prescription is withdrawn; the specs were sound and the
   mortality was the detector's.
6. REVISED AGENDA: (a) contract-drift migrates from prose landing to a
   deterministic gate (stale-reference detection over the diff) and
   consultation pins at every agent-bearing phase — the T2 §5.3 split
   assigns the whole gap to delivery form, and SPEC §4.4.2.a names the
   migration as the peg's best outcome; (b) trap-b and trap-c retire
   at this tier (bench-trap retirement); (c) run-trap.bb detection
   moves to the snapshot record.

AMENDMENT 2026-09-03 (instrument only): run-trap.bb captures each run's
full dogfood stdout+stderr to eval/codex-traps/logs/<arm>-<trap>-<rep>.log.
The :implementer/prompt-sections ground truth is a LOGGER line, present
only in that stream (stream dumps are the model's output; events are a
subset), and the harness was discarding it. Applied mid-series 3 from
rs3 onward; rs1/rs2 have gate-history.edn but no prompt log.

AMENDMENT 2026-09-03 (bench defect, provisioning): a provisioned
sandbox's local `main` and `origin/main` were the launching checkout's
copies, not the pin — in the series-3 sandbox both sat at d2f0860b
(#1744) while HEAD was the pin c87b55e7a. Anything that branches from
`main` by name (the dag-executor's default base ref) started three weeks
stale; 69 task-* branches in that sandbox were "Created from d2f0860b".
The two task branches the rs1 verdict was read from chain to the pin,
so the rs1 evidence stands; series 4 provisions with `bench:provision`
pinning both refs (tasks/bench.clj, this PR).
