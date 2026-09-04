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

## REPAIR DEMONSTRATION THIRD SERIES — rs1 FORENSICS (2026-09-03)

Ground truth from gate-history.edn (run 4ad44a07, pin c87b55e7a):
implement decision :allow on all five iterations; every deny came from
:policy-verify at verify (7 entries). :stale-references never fired.
Task branch task-83dbab21: ledger.clj/interface.clj/report.clj rename
complete, bb.edn codex-gap-report still reads :skipped, and the changed
interface_test.clj retains `{:status :skipped}` twice (a different
sense). By the gate's definition ("absent from EVERY changed file")
:skipped was never a removed token, so nothing was searched. Had it
been, the unscoped repo-wide search would have returned 76 files using
:skipped — noise, not evidence.

Consequence for the series: rs1's :sprung is a gate-definition miss,
not an implementer or delivery miss. rs2/rs3 run the same pin and
count for this pre-registration as-is; a fourth series is required at
the fixed pin. The persist commit ("implement phase completed") lands
AFTER the gate, so iteration 1 does run on uncommitted work — the
before/after mechanics were not the cause.

Fix: miniforge PR #1864 — per-namespace-family removal (tests are not
producers nor a token's new home) and consumer-scoped search (files
that name the family; namespaced keywords stay repo-wide). Unit fixture
reproduces the rs1 shape exactly.

## PRE-REGISTRATION — REPAIR DEMONSTRATION FOURTH SERIES (rt1–rt3)

Pin: the main commit that merges miniforge PR #1864 (consumer-scoped
stale-references), recorded in runs.edn per row as before.
Trap: trap-a only, baseline arm, codex pinned, 3 reps, 90-minute
budget each, same endpoints as series 3.

Hypothesis H4: with per-family removal and consumer-scoped search, the
implement gate denies the sprung tree on its first iteration
(gate-history.edn shows :stale-references among failing gates with
token ":skipped", family "ai.miniforge.codex-gap", file bb.edn), and
the :on-fail :implement retry carries :task/gate-failures
(eval/codex-traps/logs/<arm>-<trap>-<rep>.log shows :implementer/prompt-sections including
:gate-failures).
Success: >=2/3 reps :caught (bb.edn updated) within budget.
Falsifier A: gate fires, retries carry the section, tree still sprung
-> implementer ignores delivered evidence (attention leak).
Falsifier B: gate fires, section absent from retry prompt -> delivery
bug in the redirect path.
Falsifier C: gate silent again -> read gate-history + task branch
before any other conclusion; do not infer from verdicts alone.
Nothing in this pre-registration is edited after launch; results are
appended below it.

AMENDMENT 2026-09-03 (record keeping): run-trap's per-rep clean-tree
reset reverts uncommitted edits to tracked files, so the FORENSICS and
PRE-REGISTRATION sections above, first appended to the sandbox runsheet
between the rs2 and rs3 launches, were lost from the sandbox at the rs3
launch and are restored here verbatim from the session record. Pre-
registration timing stands: it was written before rs2 finished and
before any series-4 rep ran. From series 4 on, runsheet records are
written to the tracked branch, not the sandbox copy.

rs2 (2026-09-03, 69 min): :sprung. gate-history.edn (run b03bfe7d):
implement :allow x5, verify :deny[:policy-verify] x7 — identical shape
to rs1. Same cause (gate definition), no new information about the
implementer. No prompt log (pre-amendment launch).

AMENDMENT 2026-09-03 (series 4 pin): the fourth-series pre-registration
names "the main commit that merges #1864". The harness fixes (#1865:
provision pins both `main` and `origin/main` to the pin; run-trap captures dogfood stdout+stderr per rep) merged after
it as d6d5d77c5, which contains #1864. Series 4 pins d6d5d77c5 so the
provisioning defect above cannot recur in the sandbox under test.
Hypothesis, endpoints, and success criteria are unchanged.

AMENDMENT 2026-09-03 (series 4 pin, second): #1866 (human log format
carries :data — without it the prompt-sections line is a bare name, as
rs3's log shows) merged as 25f017442 after d6d5d77c5. Series 4 pins
25f017442 so H4's retry-prompt evidence is readable. Nothing else changes.

## REPAIR DEMONSTRATION THIRD SERIES RESULTS (rs1–rs3, pin c87b55e7a)

| rep | verdict | minutes | gate-history (run) |
|-----|---------|---------|--------------------|
| rs1 | :sprung | 85 | 4ad44a07: implement :allow x5, verify :deny[:policy-verify] x7 |
| rs2 | :sprung | 69 | b03bfe7d: same shape |
| rs3 | :sprung | 88 | f50d4de4: same shape |

0/3. Pre-registered H3 (repair retry fixes the consumer) is not tested
by this series: the implement gate never denied, so no repair loop on
the trap ever ran. Every redirect was verify's :policy-verify. The
cause is the gate's definition (rs1 FORENSICS above), reproduced in a
unit fixture and fixed in #1864; the series is a clean measurement of
that defect, not of the implementer.

Prompt-sections telemetry: rs3's log carries 6 :implementer/
prompt-sections lines but no payload (the human log format dropped
:data, fixed in #1866). Falsifiers A/B remain untested.

Bench validity: sandbox `main` was stale (#1744) throughout — the two
task branches per run that the verdicts were read from chain to the
pin, so verdicts stand; provisioning fixed in #1865.

Series 4 (rt1–rt3) at pin 25f017442 follows immediately.

## REPAIR DEMONSTRATION FOURTH SERIES RESULTS (rt1–rt3, pin 25f017442)

Sandbox provisioned with HEAD, `main` and `origin/main` all at 25f017442
(the #1865 fix holds in situ).

| rep | verdict | minutes | gate-history (run) | retry prompt |
|-----|---------|---------|--------------------|--------------|
| rt1 | :sprung | 15 | b5ed6db2: implement :deny[:stale-references ":skipped"] x4 | iter2/iter3 [:task/gate-failures] |
| rt2 | :sprung | 11 | 4a7bce3e: same | same |
| rt3 | :sprung | 11 | 20775bdd: same; entries 3–4 no longer list bb.edn | same |

0/3 on the pre-registered endpoint. H4's first half holds three for
three: the gate denies the sprung tree on its first iteration, naming
token ":skipped", family "ai.miniforge.codex-gap", file bb.edn first,
and the :on-fail :implement retry carries :task/gate-failures
(eval/codex-traps/logs/baseline-trap-a-rt{1,2,3}.log, readable since
PR #1866). Falsifier B is ruled out. Falsifier A is what happened, with an
aggravator the pre-registration did not anticipate:

Every denial listed six files: bb.edn (real), interface_test.clj (real,
three ledger-sense uses), classify.clj (same component, `:status
:skipped` enum — spurious), stale_references.clj and
stale_references_scope_test.clj (the gate's own docstring and fixture
naming the namespace — spurious), gap_wiring_test.clj (spurious).
rt1 and rt2 fixed the test file, wrote "the root cause of the repeated
gate denial was the test file", and never opened bb.edn (model stream
for rt1: zero mentions of bb.edn). rt3 fixed bb.edn — its entries 3
and 4 no longer list it — and was still denied on the four spurious
files until max-consecutive-phase-retries ran out. Because implement
never passed, persist never committed, and the detector read the
unfixed tree: rt3's :sprung is "fixed in the worktree, blocked by false
positives, never persisted".

Reading: a deterministic gate that names spurious files is worse than
prose — it sends the implementer to the wrong file with authority, and
when the implementer is right it still denies. Fix: importers are files
that require the family, excluding test files and the producer's own
component (verify's tests cover both); each stale file's first matching
line rides in the message. Miniforge PR: fix/stale-references-evidence-
precision (number recorded in the series-5 pre-registration below).

Bench observation: with the gate firing, reps take 11–15 minutes
instead of 69–88 — the retry budget is exhausted at implement and the
run terminates without reaching verify.

## PRE-REGISTRATION — REPAIR DEMONSTRATION FIFTH SERIES (ru1–ru3)

Pin: the main commit that merges the precision PR above (recorded per
row). Trap-a, baseline arm, codex pinned, 3 reps, same endpoints.
Hypothesis H5: the first denial names bb.edn and only bb.edn (plus
interface_test.clj is NOT listed, being a test), with its matching
line; the retry updates bb.edn; implement is allowed on iteration 2 or
3; the run reaches verify; verdict :caught.
Success: >=2/3 :caught.
Falsifier A': denial names only bb.edn with the line, retry still does
not touch bb.edn -> attention leak is not a precision problem.
Falsifier D: implement allowed but verify/other gates deny on the
consumer edit -> a different leak, read gate-history first.
Falsifier C as before. Nothing edited after launch.

AMENDMENT 2026-09-03 (series 5 pin): the fifth-series pre-registration
names "the main commit that merges the precision PR". That PR is
miniforge 1869 (PR #1869); its merge commit is c4e52062b. Series 5
(ru1–ru3) provisioned with HEAD, `main` and `origin/main` all at
c4e52062b.

## REPAIR DEMONSTRATION FIFTH SERIES RESULTS (ru1–ru3, pin c4e52062b)

Sandbox provisioned with HEAD, `main` and `origin/main` all at c4e52062b.
The pre-registered reps are ru1–ru3. Rows suffixed b/c are re-launches
of a rep whose earlier launch was refused before any workflow ran;
they are not additional reps.

| rep | verdict | minutes | gate-history (run) |
|-----|---------|---------|--------------------|
| ru1 | :caught | 106 | a12e6285: implement :deny[:stale-references, bb.edn only] x1; implement :allow / verify :deny[:policy-verify] x4; terminal verify :deny x2 |
| ru2 | refused | 0.5 | none: "Backend preflight failed for claude: Process timed out after 30000ms" |
| ru3 | refused | 0.5 | same |
| ru2b | :sprung | 31 | 591fcbad: implement :deny[:stale-references, bb.edn only] x4 |
| ru3b, ru3c | refused | 0.5 | preflight timeout, same message |

Measured reps: ru1 :caught, ru2b :sprung. ru3 is unmeasured so far;
a further re-run (ru3d) is queued behind a probe that requires the
claude CLI to answer within the preflight's 30 seconds from the
sandbox (probes at 13:50–14:10Z and 14:55Z hung for 45–70 s; probes at
14:25Z and 14:51Z answered). The preflight refusals are not
measurements (exit 1 in 33 s, no workflow ran).

H5 on the measured reps: the first denial's :files carried bb.edn and
only bb.edn, in every rep (ru1, ru2b) — the precision change held in
the gate's record. What reached the implementer prompt is a separate
matter, settled by the correction below: the file name did not. ru1's
retry updated bb.edn and implement was allowed on iteration 2; ru2b's
three retries did not touch bb.edn. ru1's :caught was read from a
run-window stash snapshot (ce3a9f41); no persist commit existed
(#1871 was not at this pin). After the catch, ru1 looped at verify
four times: a test consumer in another component (gap_wiring_test.clj
line 106, excluded from importers by design and caught by verify's
tests as intended) plus :std/exceptions-as-data and :std/localization
rule violations; each verify retry read the failing test as
"environmental — stream-recovery/abort events from backend
unavailability" and re-declared the tree correct. That misattribution
is recorded as a codex harvest candidate (HARVEST-2026-09 §1).

CORRECTION 2026-09-03 (series 4 and 5, append-only): the gate-history
entries of every :stale-references denial in series 4 and 5 store the
denial :message as the bare word "stale", with hit lines rendered as
"hit". The gate's message catalog (components/gate/resources) was not
on the dogfood JVM classpath — deps.edn listed components/gate/src in
its path vectors without the resources sibling, as it did for 32
bricks — messages/t fell back silently to the key name, and the
implementer's gate-denial section rendered only :message. Predicted
section size with the bare key: 288 characters; with the real text:
408; observed retry-prompt deltas: 282–286 (rt1–rt3, ru1, ru2b). No
series-4 or series-5 retry prompt ever named bb.edn. The series-4
reading "Falsifier A, the implementer fixed the wrong file" and the
series-5 reading "Falsifier A′" are withdrawn: both were Falsifier B
(delivery) at the message layer. ru1's catch happened without the
file name in the prompt. Fix: miniforge PR #1872 (resources on every
path vector with a guard test; a missing key warns on stderr; the
section renders :files and :hits from the error map). The
"90-minute budget" was never enforced by the harness; runs end at the
redirect cap (ru1: 106 minutes).

## PRE-REGISTRATION — REPAIR DEMONSTRATION SIXTH SERIES (rv1–rv3)

Pin: the main commit that merges PR #1872 (recorded per row; the
amendment below names it once known). Trap-a, baseline arm, codex
pinned, 3 reps, same endpoints. Series 6 is the first series in which
the denial text names the file and its matching line in the prompt.
Hypothesis H6: the first denial names bb.edn with its line; the retry
prompt contains the string "bb.edn" (verified from the log's
:implementer/prompt-sections payload and the rendered denial text);
the retry updates bb.edn; implement is allowed on iteration 2; the
allowed implement is persisted to the task branch (#1871 is at this
pin, so any skip is logged with its reason); verdict :caught read
from the branch, not a snapshot.
Success: >=2/3 :caught with the fix on the task branch.
Falsifier A″: the prompt names bb.edn with its line and the retry
still does not touch it — the attention leak, now actually tested.
Falsifier D: as in series 5 (verify loop after the catch) — recorded,
not counted against H6.
Falsifier E: persist skipped or rejected after an allowed implement —
read the :workflow/persist-skipped reason first.
Nothing edited after launch.

AMENDMENT 2026-09-03 (fifth series, ru3d): re-launch ru3d (pin
c4e52062b, run af4c5dbc, 97 minutes) — :sprung. Implement iteration 1
was ALLOWED with bb.edn untouched: the implementer renamed the ledger
key in ledger.clj and interface.clj but kept `:skipped` once in
report.clj as a message-template parameter key, and the gate's
family-wide removal rule ("absent from every changed non-test file")
read the token as moved. Verify then denied on :policy-verify five
times to the redirect cap (tests-pass passed in this rep). Fifth
series final: ru1 :caught, ru2b :sprung, ru3d :sprung — 1/3 measured.
Fix: miniforge PR 1874 (#1874) — removal judged per producer file, and
the error names where a token survives (`:survives-in`).

AMENDMENT 2026-09-03 (sixth series pin): the pre-registration names
the merge of PR 1872 (#1872). Series 6 pins the merge of PR 1874
(#1874) instead, ffb335599, which contains #1872, because ru3d showed
the family-wide exemption passing a sprung tree even with the denial
text repaired. H6 and its falsifiers are unchanged. Launch is chained
to ru3d's row landing (16:39Z).

## PRE-REGISTRATION — REPAIR DEMONSTRATION SEVENTH SERIES (rw1–rw3)

Written 2026-09-03 18:05Z, before any seventh-series rep ran; series 6
(rv1–rv3) is still in progress and its results are recorded
separately after it ends.

Pin: 228eca5c1 — main after PR 1876 (#1876, bb.edn task path lists
carry resources, so the denial text renders) and PR 1877 (#1877,
persist commits unsigned and every git step's exit reported). Trap-a,
baseline arm, codex pinned, 3 reps, same endpoints. Launch is chained
to the end of series 6.

Hypothesis H7: the first denial names bb.edn with its matching line
in rendered text (gate-history :message reads the catalog sentence,
not the bare key); the retry updates bb.edn; implement is allowed on
iteration 2; the allowed implement is persisted (log line
:workflow/workspace-persisted, task branch ahead of the pin); verdict
:caught is read from the task branch, not a stash snapshot (row
:task-branches non-empty, :snapshots empty or unused).
Success: >=2/3 :caught with the fix committed on the task branch.
Falsifier A″ (as series 6): file and line in the prompt, retry does not
touch bb.edn.
Falsifier D (as series 5/6): verify loops after the catch on policy
rules or a test consumer — recorded, not counted against H7.
Falsifier E′: persist still rejected or skipped after an allowed
implement — read the :workflow/persist-* log line first; a new reason
is a new defect, the same reason is a failed fix.
Falsifier F: the message renders but :files/:hits differ from the
message text — the two carriers disagree; read gate-history.
Nothing edited after launch; results appended below.

## REPAIR DEMONSTRATION SIXTH SERIES RESULTS (rv1–rv3, pin ffb335599)

Sandbox provisioned with HEAD, `main` and `origin/main` all at
ffb335599. Reps launched back to back with no preflight refusals.

| rep | verdict | minutes | first denial (run) | retry prompt | provenance |
|-----|---------|---------|--------------------|--------------|------------|
| rv1 | :caught | 72 | 3ee9be72: [bb.edn], hit bb.edn:648 | +382 chars | stash snapshot c77f4a00 |
| rv2 | :caught | 94 | d54dafc7: [bb.edn] | +382 chars | stash snapshot 83b146dd |
| rv3 | :caught | 80 | 73b2c851: [bb.edn] | same shape | stash snapshot 74f3a178 |

3/3 on the pre-registered endpoint. H6 held on every clause it could
be tested on at this pin: the first denial's :files carried bb.edn and
only bb.edn with its matching line in :hits; the retry prompt grew by
382 characters — the 288-character bare-key section plus the
structural "files still referencing it: bb.edn" and "bb.edn:NNN:
..." lines — so the file name and line reached the implementer; the
retry updated bb.edn and implement was allowed on iteration 2 in
every rep. Falsifier A″ (evidence in the prompt, not acted on) did not
occur. Falsifier D occurred in every rep after the catch (verify loop
on :policy-verify to the redirect cap); recorded, not counted.

Falsifier E fired in every rep: the persist step logged
"persist-rejected ... :archive-commit-failed — error: 1Password:
failed to fill whole buffer" at implement and verify. The scratch-
worktree commit inherited the operator's global commit signing and a
locked signing agent; this is the mechanism behind every "fixed but
never on the branch" outcome since series 4. Fixed in PR 1877
(#1877): persist commits run unsigned and every git step's exit is
reported. All three verdicts were therefore read from run-window
stash snapshots, not task branches.

Two observations for the record. The denial :message text was still
the bare key at this pin: the `bb miniforge run` task's path list in
bb.edn lacked the gate's resources, a second path list with the same
gap #1872 closed in deps.edn; fixed in PR 1876 (#1876). And the
missing-key warning from #1872 surfaced four more bare keys in the
verify phase (:policy/violation-summary, :decide/gate-failed,
:decide/policy-rule-violated, :decide/missing-artifact), all from the
same cause.

The seventh series (pre-registered above at 228eca5c1) tests the
committed path with the message text rendered.

## PRE-REGISTRATION — EIGHTH SERIES, TREATED ARM (rx1–rx3)

Written 2026-09-03 22:16Z, before any eighth-series rep ran; series 7
(rw1–rw3, baseline) is in progress (rw1 :caught from the task branch)
and its results are recorded separately when it ends.

Pin: the main commit that merges PR 1880 (#1880, §7.7 per-peg
telemetry), recorded per row. Trap-a, TREATED arm (codex present,
pinned to the vault as of launch), 3 reps, same endpoints as series 7.
Launch is chained to the end of series 7 and the pin being set.

Purpose: two measurements no earlier series could make. First, the
codex's marginal effect with the gate on — series 7 measures the gate
alone; this arm adds the consultation. Second, the first §7.7 record:
the treated arm's ledger presents pegs, and gate-history records the
mechanism's verdict per implement iteration, so
`bb codex-gap-peg-telemetry` has real input for the first time.

Hypothesis H8a (catch): >=2/3 :caught with the fix on the task branch,
matching or exceeding series 7's baseline rate (pin 228eca5c1; the
pins differ only by #1880, a read-only telemetry tool). The
codex is not expected to lift trap-a's catch rate above a working gate;
a drop would be the finding.
Hypothesis H8b (telemetry): after the series, the per-peg record for
the peg landing on `contract-drift-is-silent` shows :observed? true,
observations equal to the number of implement iterations across the
three runs, answers {:denied 3 :allowed >=3} (one denial per rep
before the retry), entropy well above the floor, no trigger.
Falsifier G: the treated arm consults but the ledger records no
:miss/pegs (delivery of the telemetry basis broken) — read
codex_pin.clj's attach-consultation path first.
Falsifier H: catch rate below series 7's — the consultation's prose
displaces the gate evidence in the prompt; measure prompt sizes.
Falsifiers A″, D, E′, F as in series 7. Nothing edited after launch.

## REPAIR DEMONSTRATION SEVENTH SERIES RESULTS (rw1–rw3, pin 228eca5c1)

Sandbox provisioned with HEAD, `main` and `origin/main` all at
228eca5c1. Reps launched back to back, no preflight refusals.

| rep | verdict | minutes | first denial (run) | persist | provenance |
|-----|---------|---------|--------------------|---------|------------|
| rw1 | :caught | 69 | c8f04904: rendered, bb.edn:649 | commits | task branch task-3c4dd5af, no snapshot |
| rw2 | :caught | 66 | 5b2da752: rendered, bb.edn:649 | commits | task branch task-0f6ba7bf, no snapshot |
| rw3 | :caught | 56 | c4dfe385: rendered, bb.edn:649 | commits | task branch task-5873f944, no snapshot |

3/3 on the pre-registered endpoint, and every clause of H7 held in
every rep: the gate-history :message reads the catalog sentence with
the file and its matching line ("':skipped' was removed by this change
but is still referenced by: bb.edn — bb.edn:649: ..."); the retry
prompt carried it (+504 characters over the bare prompt: the rendered
sentence plus the structural file and hit lines); the retry updated
bb.edn; implement was allowed on iteration 2; the allowed implement
was committed to the task branch ("implement phase completed", then
"verify phase completed" carrying the fix; each branch two or three
commits ahead of the pin); and the verdict was read from the branch —
row :snapshots and :worktrees empty in all three. Falsifier D occurred
in every rep after the catch (verify looped on :policy-verify to the
redirect cap); recorded, not counted. Falsifiers A″, E′ and F did not
occur.

Observation for the record: at a denied implement boundary the persist
log says "Nothing to persist at implement: worktree clean" while the
task branch nonetheless gains an "implement phase completed" commit at
the same second — the worktree tier commits inside archive-bundle!
before the boundary log runs its own clean check. Harmless; the branch
is the record.

This closes the trap-a arc opened in series 1: prose 0/8, blind gate
0/3 twice (series 3, 4), evidence-less gate 1/3 (series 5),
evidence-in-prompt 3/3 read from snapshots (series 6), evidence-in-
prompt with persisted fixes 3/3 read from branches (series 7). Series 8
(pre-registered above) adds the codex consultation on top of the gate
and produces the first §7.7 per-peg record.

## REPAIR DEMONSTRATION EIGHTH SERIES RESULTS — TREATED ARM (rx1–rx3, pin ed8967bff)

Sandbox provisioned with HEAD, `main` and `origin/main` all at
ed8967bff; MINIFORGE_CODEX_PATH set to the vault; every phase's
consultation recorded :pinned? true (plan situation
about-to-commit-consequential, implement changing-one-side-of-a-
boundary). Reps launched back to back, no preflight refusals.

| rep | verdict | minutes | first denial (run) | provenance |
|-----|---------|---------|--------------------|------------|
| rx1 | :caught | 62 | d1ae186b: `bb.edn` | task branch, no snapshot |
| rx2 | :caught | 67 | 77c18220: `bb.edn` | task branch, no snapshot |
| rx3 | :caught | 62 | f413dd80: `bb.edn` | task branch, no snapshot |

H8a (catch): 3/3, equal to series 7's baseline 3/3 at the pin one
read-only tool behind. The codex neither lifted nor lowered trap-a's
catch rate with the gate on, as pre-registered; Falsifier H (prose
displacing the gate evidence) did not occur. Every rep followed the
series-7 shape exactly: deny on iteration 1 naming bb.edn, retry
fixed, allowed on iteration 2, persisted, verify loop on policy rules
to the cap (Falsifier D, recorded).

H8b (telemetry): held on every clause. Each implement consultation
recorded nine pegs in the production shape (keys `:id`, `:answer`
holding nil, and `:landings` mapping each answer to its landing ids);
Falsifier G did not occur. `bb codex-gap-peg-telemetry` over the three dag-task
checkpoints (record kept at eval/codex-traps/telemetry/
series-8-peg-telemetry.edn):

| peg | observed | observations | answers | entropy (bits) | trigger |
|-----|----------|--------------|---------|----------------|---------|
| contract-verified-against-producer | yes (miniforge/gate/stale-references) | 15 | denied 3, allowed 12 | 0.722 | none |
| eight others | no mapped mechanism | 0 | none recorded (`{}`) | 0.0 (no observations) | none |

Observations equal the implement iterations across the three runs
(five per rep); the three denials are the three first-iteration
denials. No peg's answer branches collapsed. The first §7.7 record is
therefore consistent with the gate histories it was derived from, and
§4.4's trigger has an input for the first time; it does not fire, as
expected for a peg whose mechanism denies once per run and allows
thereafter.

Arc summary (trap-a, this bench): prose 0/8 → gate blind 0/3, 0/3 →
gate without evidence 1/3 → evidence in prompt 3/3 (snapshots) →
evidence and persisted fixes 3/3 (branches) → codex on top 3/3
(branches) with telemetry.

## Series 8 forensics — Falsifier D root cause (2026-09-04)

Every series since the fifth ended each catch with a verify loop to the
redirect cap (Falsifier D, recorded, never explained). Checkpoint
f413dd80 (rx3) explains it; rx1 and rx2 match line for line.

1. The verify run really failed. Its stored output is 215,100 chars,
   6,681 lines, with three `FAIL in` blocks. One is the trap's own
   consequence: the task renamed `:skipped` to `:torn-lines`, and a
   consumer test in phase-software-factory (gap_wiring_test.clj:106)
   still expects `:skipped`. The stale-references gate exempts tests by
   design (PR 1869 (#1869)), so verify was the mechanism that had to
   name it.
2. The parser took the first namespace's `Ran 3 tests … 0 failures, 0
   errors` for the whole run and synthesized one error from the
   non-zero exit. Summary: "Tests failed: 0 failure(s), 1 error(s)";
   metrics: pass-count 3, fail-count 1. The tests-pass gate passed on
   every iteration (it reads artifact metadata the verify path never
   sets); only the policy judge and the nil-output reason denied.
3. The implementer's excerpt keeps 30 head and 25 tail lines; every FAIL
   block sat in the omitted middle. The implementer ran the suite
   itself, saw OCI-timeout lines from unrelated tests, and wrote that
   the one error was "infrastructure flakiness unrelated to this
   rename": `:already-implemented`, five times, to the cap.
4. The other two FAIL blocks, at lines 550 and 572 of
   `components/phase-software-factory/test/ai/miniforge/phase_software_factory/release_test.clj`,
   only occur with `MINIFORGE_CODEX_PATH` exported: behavior loading
   consults the codex through the environment and appends the
   consultation to the release addendum. Treated-arm noise, not a task
   consequence; the baseline arm sees one failure, the treated arm three.

Fix: PR 1884 (#1884) sums every namespace, keeps each FAIL/ERROR block with
name, location and detail, names the failing tests in the verify
summary, and leads the implementer's excerpt with the blocks. Checked
against the rx3 output: all three named. Filed separately: the
tests-pass gate reading verify metrics; the host executor honoring its
timeout and draining both streams; tests isolated from the codex
environment variable and from the enclosing repo (the suite created 108
`task-*` branches in the sandbox in one minute).

Reading for the harvest: "failing test read as environmental" was not
a discriminator the implementer lacked. The mechanism handed it a wrong
summary and an excerpt with the evidence cut out; the misread followed.
Re-observe after the fix before admitting anything.
