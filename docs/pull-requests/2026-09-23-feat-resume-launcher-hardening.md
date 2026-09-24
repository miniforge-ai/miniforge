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
- A launch with no pid recorded counts as running until 60 s after it began.
  A child gone when it is recorded is marked exited and never counts as
  running.
- Without `setsid`, the child gets a process group of its own through
  `/bin/sh` job control. A signal to the consumer's group does not reach it.
- The launch record keeps the dispatched intervention and its `from-phase`.
  `settle!` marks it with the final state, and `pending-launches` lists
  records never settled. PR 6's server uses them to finish verifications a
  stopped server left.
- An interrupted wait returns `{:resume/pending? true}`: nothing is recorded,
  and the child keeps running.

## Testing Plan

- Records: an archived run keeps its origin; the runner's pid is a live target
  until released.
- Records: a pid-less launch within and past its window; a child gone before
  it was recorded; settle and pending.
- Launcher: a live recorded runner refuses the retry; the detached child is
  outside the consumer's process group; an interrupted wait is pending.
- Pre-commit hook per commit.

## Deployment Plan

No migration. Nothing is registered until PR 5.

## Known Limits

A runner killed with SIGKILL never releases its origin. Its pid is dead, so
the run is not taken for live, unless the pid is reused by a process with the
same start instant.

## Related Issues/PRs

Stack: `feat/resume-flags`, `feat/operator-async-resume`,
`feat/resume-launcher`, this PR, `feat/shared-process-handles`,
`feat/operator-serve`.

## Checklist

- [x] Tests for new behaviour
- [ ] Review comments addressed; CI green
