<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(eval): track the codex seeded-trap bench harness, gated on sandbox isolation

## Overview

Bring `eval/codex-traps/` into the repo and refuse to run it outside an
isolated bench sandbox.

Stacked on [#1685](https://github.com/miniforge-ai/miniforge/pull/1685),
which adds `bb bench:provision` and `bb bench:verify`. Merge that first.

## Motivation

The seeded-trap bench is an A/B experiment over repeated dogfood runs:
does a run with the codex avoid a seeded failure mode that a run without
it walks into? Its harness — runsheet, runner, detectors, spec masters —
existed only on disk inside `~/.miniforge/bench/repo/eval/codex-traps/`
and was never committed. The instrument that decides every verdict had
no version history, no review, and no evidence that the pre-registration
predated any run.

The runner also had no idea which repository it was operating on. It
runs `git reset --hard` and then a dogfood run that ends in a push. The
2026-08-06 incident (#1685) is what that costs when the answer is "the
live checkout".

## Layer

Evaluation harness (`eval/`), not a runtime brick and not on a source
path.

## Changes in Detail

### The gate

`isolation-anomaly` refuses to start unless **both** hold:

1. `bb bench:verify <repo> [$MINIFORGE_BENCH_SOURCE]` exits 0 — the
   sandbox does not share a git common dir with another checkout.
2. The sandbox's `origin` is the bare mirror sitting beside it, at
   `<root>/origin.git`.

Condition 2 is the one that only exists because this PR tracks the
harness. Before, the file lived nowhere but the bench. Now
`bb eval/codex-traps/run-trap.bb baseline trap-a 1` is a thing anyone
can type in an ordinary checkout — which is a plain clone, so
`bench:verify` would call it isolated and the run would reset that
checkout and push for real. Condition 1 protects the *launching*
checkout from the bench; condition 2 protects everything else from the
runner.

Anything the guard cannot determine is a refusal: no `bb.edn` at the
resolved root, a `bb` it cannot launch, an unreadable `origin`, a
missing mirror, a mirror that is not bare. A refusal exits 2 — distinct
from 0 and 1, so it cannot be read as a run that passed or failed — and
touches nothing.

`bb bench:verify` is invoked from `$MINIFORGE_BENCH_SOURCE` when that
names a checkout, and from the sandbox otherwise. The dirs it judges are
its arguments, not its working directory, so a sandbox pinned to a ref
predating the task can still be gated. That matters here: the bench's
pre-registered pin `bade0222fa6` is older than #1685.

### Codex path

`codex-path` read a literal
`/Users/chris/Library/CloudStorage/Dropbox/...` path. It now reads
`MINIFORGE_CODEX_PATH`, and the treated arm refuses to start when that
is unset or is not a directory. There is no default, deliberately: the
treated arm *is* that variable, so an absent codex would run a second
baseline under a treated label and silently halve the matrix.

### Structure

`run-trap.bb` is layered per rule 210 (three strata, `^{:stratum N}`
metadata, no same-layer calls) with anomaly-valued failures per 005: git
and `bb` shell-outs come back as values, and only the CLI tail exits.
A failed `git reset` is now reported rather than thrown, because an
unreset tree makes the next verdict unattributable.

`detect.bb` gains the license header, an `ns` form, layer headings and
stratum metadata. Every detector expression is byte-identical to the
frozen version; only the signatures, headings and the CLI tail's
dispatch changed.

### From review

- `detect` was total only by luck: a detector that exited non-zero, said
  nothing, or printed non-EDN threw out of `edn/read-string` and took
  the run's record with it — *after* the run had cost hours. It now
  yields `:detector-error`, which outranks nothing in `verdict-rank`, so
  it surfaces only when no real verdict exists and can never be confused
  for one.
- The mirror check compared `origin` as a raw string. Git resolves a
  relative remote against the repo; `fs/directory?` resolved it against
  the process's working directory. A sandbox could therefore be accepted
  or refused depending on where `bb` was run from — and the accept
  direction is the dangerous one. `local-dir` now resolves `origin`
  against `repo` before comparing, and the bare-repo check uses the
  resolved path.
- Arm state (`MINIFORGE_HOME`) was a fixed `~/.miniforge/bench/home/<arm>`
  regardless of which sandbox was running, so two sandboxes shared one
  event stream — and per-run attribution is a before/after diff of that
  stream. It is now `<sandbox-root>/home/<arm>`, identical for the
  default sandbox. Found the hard way: a gate test from a throwaway
  sandbox wrote two workflow ids into the real bench's baseline event
  dir. They are logged in the runsheet amendment and left in place.

### Runsheet

`RUNSHEET.md` is a scientific pre-registration, so step 4's origin
redirect is marked superseded **in place** and the replacement is logged
as `AMENDMENT 2026-08-06 (b)`, not edited over. The amendment records
the sandbox defect, the `bb bench:provision` procedure, the gate, the
codex-path requirement, the `MINIFORGE_BENCH_SOURCE` consequence for the
pin, and the fact that `detect.bb`'s detection logic did not change.

## What is not committed, and why

**`runs.edn` is gitignored.** The runner appends one line per run to it.
That is experiment output, not instrument — the same split
`eval/policy-fidelity/` already draws with its `results/`. The two rows
on disk today are the uncounted trap-b and trap-a shakeouts, both
produced under the defective sandbox; the amendment says so in prose,
which is where that belongs.

**`eval/codex-baseline/` is not in this PR.** It is the completed
companion arm the trap runsheet references, and it is a different
decision from this one. Its runsheet is not a plan, it is the report:
its results sections cite `runs.edn` and `extract-2026-08-06.edn`, the
evidence behind a NULL primary and an all-`:uncovered` ledger. Committing
the scripts without that data leaves a report citing files that are not
there; committing the data contradicts the rule that keeps `runs.edn`
out of `codex-traps`. It also carries the same hardcoded personal path
and ungated `git reset --hard` as this runner did, plus
`recover-spec1.bb`, a self-described one-off that rule 008 would delete.
Archiving a closed experiment deserves its own PR where that question
gets answered on its own terms, rather than being decided by proximity.
Until then, the trap runsheet's reference to
`eval/codex-baseline/RUNSHEET.md` points at a file that is not in the
repo.

## Known deviations

- **Rule 050 (localization).** The script prints prose directly.
  A bb script's classpath carries `bb-utils` only, so there is no
  message catalog to look up — the same constraint that makes
  `tasks/bench.clj` build its own local `anomaly`. Recorded, not waived.
- **Rule 740 (bb-over-shell).** This stays a script rather than a
  `bb.edn` verb over a `tasks/` namespace. 740's globs are `scripts/**`
  and `tools/**`; `eval/**` is the existing home for harnesses invoked
  by path (`eval/policy-fidelity/run.clj`), and the pre-registration
  names the invocation `bb run-trap.bb`.
- **`run-trap.bb` has no `ns` form.** clj-kondo requires a namespace to
  match its file name character for character, and the runsheet names
  the file with a hyphen. `detect.bb` keeps its `ns`.

## Testing

No automated test, and that is a gap, stated plainly. Every branch of
the gate is a property of real git state, so a test would have to shell
`bb run-trap.bb` at throwaway repos the way `development/test/bench_test.clj`
already shells real git; that is ~30 reportable lines against 35 of
remaining PR budget, which is too tight to also absorb a review round.
The better home is `bench/isolation-report` — see Follow-ups.

Verified by hand against a sandbox provisioned with `bb bench:provision`
into a throwaway root, using the treated arm with no codex as the stop
so the isolation check could be exercised without starting a dogfood
run.

| Sandbox state | Result |
| --- | --- |
| Linked worktree of another checkout | refused (`bench:verify`), exit 2 |
| Provisioned clone, `MINIFORGE_CODEX_PATH` unset | isolation passed; refused on codex |
| Provisioned clone, `MINIFORGE_CODEX_PATH` not a directory | refused on codex |
| `origin` repointed at `github.com/miniforge-ai/miniforge` | refused (not the mirror) |
| Mirror absent | refused (no mirror) |
| `origin` a local non-mirror repo | refused (not the mirror) |
| Non-bare repo at the expected mirror path | refused (not bare) |
| `MINIFORGE_BENCH_SOURCE` set to the launching checkout | isolation passed |
| Harness copied to a directory too shallow to resolve a root | refused (no `bb.edn`) |
| `origin` set to a *relative* path resolving to the mirror | isolation passed, from two different working directories |

`detect` was exercised against a detector that exits 3 printing non-EDN,
one that exits 0 printing nothing, and the real `detect.bb`: the first
two yield `:detector-error` with the exit code and raw output as
evidence, the third its normal verdict.

Each refusal exited 2 and left the tree untouched. `bb bench:provision`
left the launching checkout's `remote.origin.url` unchanged.

`detect.bb` still reads `:not-reached` for trap-a, trap-b and trap-c
against a pristine tree — the self-test the runsheet records for the
frozen detectors.

`~/.miniforge/bench/` was read-only throughout; a run was in flight
against it when this work started.

## Follow-ups

- Move the mirror check into `bench/isolation-report`. It is not
  trap-specific — "this sandbox's origin can still reach a real remote"
  is true of any bench runner — and there it is a pure function over a
  report map, unit-testable in the `bench_test.clj` that #1685 already
  adds. It sits in the harness here only because #1685 is still in
  review and widening it from a stacked PR is the wrong move. That
  relocation is where the automated test belongs.
- Decide `eval/codex-baseline/`: archive the closed experiment with its
  data, or keep it out of the repo. Until then the trap runsheet's
  companion reference does not resolve.
- The counted matrix needs a sandbox provisioned with
  `bb bench:provision` and `MINIFORGE_BENCH_SOURCE` exported, and the
  chosen pin logged in the runsheet.
- `heads/main` is still present in the live checkout
  (`git branch -D heads/main`), left alone because a bench run was in
  flight. Carried over from #1685.
