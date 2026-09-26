<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: review fixes to the resume launcher

## Overview

Inserted above `feat/resume-launcher` (#1915), below
`feat/resume-launcher-hardening`. Review fixes to the launcher from #1915.
They would put #1915 past the 600-line PR budget, so they are a PR of
their own.

## Motivation

Copilot's review of #1915:

- `--from-phase` was said to be prepended to the child's argv. It is not:
  the options follow `resume <id>`. A test now runs the argv through the
  CLI's own dispatch.
- The launcher detaches through `/bin/sh`, `nohup` and `setsid`. Native
  Windows, a supported CLI platform, has none of them.
- A poll that throws ended the start wait with an exception. The
  verification pool records that as `:application-error`, and the child is
  never killed.
- A launcher that died between the pre-spawn record and the pid record
  left a detached child nothing could find: the wait after a restart had
  no pid to watch or kill.
- A retry with an FSM snapshot ran under the snapshot's run id. A finished
  run's events are archived (`archived/<id>`) and the resumed runner writes
  `live/<id>`. Readers that prefer `archived/` never saw the attempt:
  reconstruction and the readback here, and from #1916 also the start wait
  (a timeout, then a duplicate retry) and the live-runner check. Archival
  could not merge the two directories either.

A review of the fresh run id found that attempts need a lineage:

- The launch record was keyed by the retried workflow. After run W was
  retried as attempt A1, a retry of W and a retry of A1 had separate
  records, so both could start, in the same directory, at once.
- A retry of W after A1 ran restarted from W's state. It dropped A1's
  progress without saying so, and could restore W's bundle onto A1's
  branch.
- An attempt had no workspace checkpoints of its own, so a rewind of it
  started from a fresh workspace while keeping phases whose work that
  workspace lacked. Also, a checkpoint's phase was never read at all: the
  runner's event names it `:workspace/phase`, and the reader looked for
  `:workflow/phase`. Every `--from-phase` rewind since #1913 therefore
  started from a fresh workspace.
- `mf resume W --run-id X`, with X an existing run, wrote into X.
- A new attempt restored the retried run's metrics, so its cost counted
  the earlier run's too.

## Changes in Detail

- The child's argv is checked through `mf`'s dispatch: it runs `resume`
  with the workflow id, `--run-id`, `--correlation-id` and `--from-phase`.
- Native Windows: `launcher` returns nil there, so no launcher is
  registered and a retry fails `:no-resume-launcher`. The message for that
  code now names both causes: native Windows, and an unknown command
  (`MINIFORGE_CMD`). `launcher` takes its facts (`os-name`, the command
  line) as an argument too, for tests.
- A start-wait poll that throws counts as a poll without evidence, and the
  child as alive. Only the deadline ends a wait that keeps failing: the
  child is then killed and reported not started.
- The child writes its own pid to `<home>/logs/resume-<run-id>.pid` before
  it runs `mf resume`: an inner `/bin/sh` writes `$$` and execs the
  command, so the pid is the command's. The launch record names the file
  before the spawn, and a stale file of the same name is removed first.
- `with-child-pid`: a record without a pid takes the one in its pid file,
  while that process started between the launch and the file's writing (a
  pid reused later, or a process older than the launch, is not the
  child). A pid file naming no such process marks the launch
  `:resume/exited?`. The start wait and the in-flight check use it, and the
  wait reads the pid file again at the deadline, so a silent child is
  killed.

### A retry is a new attempt with a run id of its own

- The launcher gives every launch a fresh run id, with or without a
  snapshot in the plan. A `:retry-from-phase` already got one; a plain
  `:retry` now does too.
- `mf resume --run-id <uuid>` beside a restored snapshot runs the
  snapshot's state under that id. Before, a different id was refused
  (#1913). A value that is not a UUID is still refused; without
  `--run-id`, a snapshot still resumes under its own id.
- The attempt's events land in `live/<new-id>`, where the start wait, the
  readback and later reconstruction look. The retried run's archive is
  never written to. Ownership and in-flight checks stay keyed by the
  retried workflow's launch record.
- A resume that starts holding restored phase results writes a phase
  checkpoint for each one its run id lacks. Before, only the last one got
  a file, so an attempt under a new id (a retry, or a `--from-phase`
  rewind since #1913) could not itself be resumed with its earlier
  phases' results. A resume under the same id finds them there and
  writes nothing.

### Attempts form a lineage

- The run first retried is the lineage's root. Each attempt a retry starts
  is named in `.resume-launches/lineage/<attempt>.edn` (`:resume/root`,
  `:resume/retry-of`), written before the spawn. It is not written into
  the attempt's own run directory, which must not exist before the child
  does.
- The latest launch is recorded once per lineage, under the root, with
  every attempt launched so far (`:resume/attempts`). One launch runs per
  lineage, whichever member it targets.
- Only the newest attempt that has recorded a run can be retried. A retry
  of the root or an older attempt is refused `:resume-superseded`, naming
  that attempt (`:resume/latest-attempt`); the operator's message says to
  retry it instead. An attempt that never recorded a run does not count.
- A live runner of any member refuses the retry. An attempt with no
  recorded origin runs in its root's.
- The child writes its pid file whole: a temp file, then a rename.

### What a new attempt carries

- A resume under another id than the run's own (any `--run-id` retry, a
  rewind, a snapshot-less resume) publishes the run's workspace
  checkpoints under the new id, with their phases, before it starts: all
  of them, or on a rewind those of the phases it keeps. A retry or rewind
  of the attempt then restores from them.
- A checkpoint's phase and tier are read from `:workspace/phase` and
  `:workspace/tier` (falling back to the old keys).
- A new attempt's restored snapshot starts with zero metrics.
- `--run-id` naming another run that has events or a checkpoint is
  refused, as `:anomalies/conflict`. The help text says what `--run-id`
  now does.

## Testing Plan

- The argv through `-main` reaches `resume` with its options, with
  `--from-phase` coerced to a keyword. (Prepended, it would arrive as a
  string.)
- No launcher on Windows; one on Linux and macOS.
- A real detached spawn writes its pid file with the pid it returns.
- A record without a pid: nothing known before the pid file, nor while it
  is empty; the live child once written; a process older than the launch
  marks it exited. The record written before the spawn names the pid file.
- A launch recorded only before its spawn is killed at the deadline by
  the pid its child wrote.
- A launch whose plan carries a snapshot still runs under a fresh id.
- Lineage: an attempt's root, and an attempt of an attempt's; a retry of
  the root after an attempt ran refused with that attempt's id, and of an
  older attempt with the newest's; an attempt with no run not counted; a
  running launch refusing a retry of any member; a live runner of the root
  refusing a retry of the newest attempt.
- The operator carries `:resume-superseded` and the attempt it names.
- `resume-test`: a new attempt records the run's workspace checkpoints
  under its id with their phases, starts at zero metrics, and a rewind of
  it restores a kept phase's workspace; a resume under the run's own id
  records nothing and keeps its metrics; a `--run-id` naming a run with
  events, or with a checkpoint, is refused.
- `core-test` (workflow-resume): a checkpoint read from the runner's own
  `:workspace/persisted` event keeps its phase and tier.
- `resume-test`: another `--run-id` beside a snapshot registers and runs
  under that id, and the snapshot handed to the runner carries it.
- `runner-test`: after a resume holding two restored results, with a
  snapshot and without, the new run id's checkpoints load both.
- A throwing poll followed by the start evidence verifies the launch; no
  run directory yet is no evidence.
- Pre-commit hook per commit.

## Deployment Plan

No migration. Nothing is registered until `feat/shared-process-handles`.

## Known Limits

- No retries on native Windows. WSL is a Linux host.
- An attempt's workspace checkpoints come from its own events. If it is
  killed before its first phase boundary, a retry of that attempt starts
  without one.
- The console does not link an attempt to the run it retried: the
  supervisory record ignores the event's correlation id. A follow-up adds
  a contract field for it.

## Related Issues/PRs

Stack: `feat/operator-verification-pool` (#1920), `feat/resume-launcher`
(#1915), this PR, `feat/resume-launcher-hardening` (#1916),
`feat/shared-process-handles` (#1917), `feat/operator-serve` (#1918).

## Checklist

- [x] Tests for new behaviour
- [ ] Review comments addressed; CI green
