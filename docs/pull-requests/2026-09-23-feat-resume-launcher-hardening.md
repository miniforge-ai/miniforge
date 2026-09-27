<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: the resume launcher sees live runners and archived runs, and hands off on restart

## Overview

Fourth of six stacked PRs. Review fixes to the resume launcher from PR 3,
before PR 5 registers it.

## Motivation

- A Babashka runner writes no manifest, so a retry could start a second runner
  of a workflow still running in another process.
- A run's origin was read only from the live directory. A finished run is
  archived, so a retry of it was always refused `:resume-origin-unknown`.
- A launch recorded without a pid counted as not running, so a second launch
  was possible. A child gone before its start instant was read left a pid that
  could later belong to any process.
- Without `setsid` (macOS), the child stayed in the consumer's process group.
- An interrupted wait reported the retry unverified while its child ran on.

## Changes in Detail

- The origin also records the runner's pid and start instant. Releasing the
  run drops them (`release-origin!`, called by PR 5). A retry is refused while
  that runner is alive.
- The origin, and the start evidence, are found where the reader finds a run's
  events: archived, live or legacy. The event-stream interface gains
  `workflow-events-dir` for this.
- A launch with no pid recorded, and no pid file from its child yet (see
  `fix/resume-launcher-review`), counts as running until 60 s after it
  began.
  A child gone when it is recorded is marked exited and never counts as
  running.
- Without `setsid`, the child gets a process group of its own through
  `/bin/sh` job control. A signal to the consumer's group does not reach it.
- The launch record keeps the dispatched intervention and its `from-phase`.
  `settle!` marks it with the final state, and `pending-launches` lists
  records never settled. The record is the lineage's, under its root (see
  `fix/resume-launcher-review`). Settling keeps the child's pid, read from
  its pid file when the launcher died before recording it, and removes the
  pid file. PR 6's server uses them to finish verifications a
  stopped server left.
- An interrupted wait returns `{:resume/pending? true}`: nothing is recorded,
  and the child keeps running.
- At the start deadline, a launch with no recorded pid reads its child's pid
  file once more, right at the kill. A child that writes its pid just after
  the wait is still killed.
- `mf resume --run-id` refuses an id that has a run directory: archived, live
  or legacy. It does so even when no event file in it parses. Before, the
  reader dropped unreadable files, and the id counted as free.
- A run whose events are archived, or in the legacy flat layout, resumes as
  a new attempt under a fresh run id. A `--run-id` naming that run is
  refused. Under its own id, the attempt would write to `live/<id>`. Beside
  an archive, the reader and archival never see those events. Over a legacy
  directory, they hide the run's own. A fresh id is used, not a refusal: the
  archived run stays as it finished, and the launcher already passes one.
- `mf resume <id> --run-id <new>` no longer throws `Duplicate key` when the
  snapshot carries the run's own id. That is the launcher's usual call.

## Testing Plan

- Records: an archived run keeps its origin. An archived attempt's start
  event is still evidence. The runner's pid is a live target until released.
- Records: a pid-less launch within and past its window, also one whose
  child has not written its pid file yet. A child gone before it was
  recorded. Settle and pending. Settling an attempt's launch recorded only
  before its spawn settles the root's record. It keeps the pid its child
  wrote and removes the pid file.
- Launcher: a live recorded runner refuses the retry. An archived attempt
  still supersedes its root. The detached child is outside the consumer's
  process group. An interrupted wait is pending. A pid file written just
  after the wait still gets the child killed.
- Resume: a `--run-id` whose run directory holds only unparseable event
  files is refused, in each layout. An archived or legacy run resumes under
  a fresh id and refuses its own. A live run keeps its id. A snapshot
  carrying the run's own id takes a new `--run-id`.
- Tests that start POSIX processes skip on native Windows
  (`posix-host/on-posix-host`). They are unchanged on macOS and Linux.
- Pre-commit hook per commit.

## Deployment Plan

No migration. Nothing is registered until PR 5.

## Known Limits

A runner killed with SIGKILL never releases its origin. Its pid is dead, so
the run is not taken for live, unless the pid is reused by a process with the
same start instant.

A child recovered without a recorded pid can still write its pid file after
the launcher's last read, just before the timeout kill decision. The launcher
reads the file again right at that decision, which narrows the window but does
not close it. Closing it needs an atomic claim on the pid file, which would
make every child's start depend on hard-link support.

## Related Issues/PRs

Stack: `feat/resume-flags`, `feat/operator-async-resume`,
`feat/resume-launcher`, `fix/resume-launcher-review`, this PR,
`feat/shared-process-handles`, `feat/operator-serve`.

## Checklist

- [x] Tests for new behaviour
- [ ] Review comments addressed; CI green
