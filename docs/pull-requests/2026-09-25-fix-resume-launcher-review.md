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
- A retry of the original run starts from the original's state, not from
  a later attempt's. To continue an attempt, retry the attempt.

## Related Issues/PRs

Stack: `feat/operator-verification-pool` (#1920), `feat/resume-launcher`
(#1915), this PR, `feat/resume-launcher-hardening` (#1916),
`feat/shared-process-handles` (#1917), `feat/operator-serve` (#1918).

## Checklist

- [x] Tests for new behaviour
- [ ] Review comments addressed; CI green
