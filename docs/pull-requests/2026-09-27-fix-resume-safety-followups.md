<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Review findings left open on the resume launcher stack

## Overview

Fix the Copilot findings on #1916 and #1917 that were resolved without a fix
before those PRs merged. Each one is a gap now on main.

## Motivation

A bulk thread-resolve closed review comments that had landed after the last
listing. Four of them describe ways the retry path can lose or corrupt state,
or harm an unrelated process.

## Layer and dependencies

CLI resume launcher and records, the `resume` command, and the workflow
checkpoint store. Follows merged #1915, #1916 and #1917. Independent of #1918.

## Changes in detail

- `--run-id` collision: `load-checkpoint-data` answers nil for phase
  checkpoints written before the snapshot and for unreadable files, so either
  made the id look free. The check is now `workflow/checkpoint-present?`:
  anything under the run's checkpoint directory holds the id.
- Pid evidence: `process-running?` needs a recorded start instant. Without
  one, a reused pid kept `target-live?` refusing a retry.
- Deadline kill: `destroy-process!` takes the pid and its start instant,
  and kills only through the handle it just checked. It does nothing
  without a start instant.
- Start window: a pid with no start instant waits out the launch window.
  A record with no launch time has no window instead of throwing.
- The 2 s slack in `pid-file-child` is documented with the pid reuse it
  admits.
- `fetch-pr-diff` answers nil when `gh` cannot start, so a re-evaluation
  refuses as `:no-diff` instead of `:application-error`.
- `workflow/interface.clj` gets the stratum annotations the pre-commit
  autofix adds (a separate commit).

## Testing plan

New tests cover an orphan phase checkpoint and an unreadable snapshot, a pid
without a start instant, destroy only by the matching pair, a real child with
no start instant surviving the deadline, a record with no launch time, an
origin with no start instant, and a `gh` that cannot start.

Local results: checkpoint store and resume tests 24 tests, 133 assertions;
resume records, launcher and operator wiring 32 tests, 133 assertions;
github 11 tests, 29 assertions. All pass. Pre-commit hooks pass on each commit.

## Deployment plan

No migration. Launch records from every merged version carry
`:resume/launched-at-ms`.

## Related issues/PRs

Copilot threads 4110390884, 4112592198, 4112592210, 4112592218, 4113555151
and 4113555159 on #1916, and 4112605117 on #1917.

## Checklist

- [x] Tests for each behavior change.
- [x] Hooks pass without skipping.
- [ ] Review comments settled and CI green before merge.
