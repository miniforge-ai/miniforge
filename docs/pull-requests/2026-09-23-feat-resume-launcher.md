<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: a resume launcher that starts a retried run at most once

## Overview

Third of five stacked PRs. Adds the CLI resume launcher that `:retry` and
`:retry-from-phase` interventions dispatch through. PR 4 registers it in every
consuming process.

## Motivation

A retried run's runner is gone, so something must start it again. It must
start once per intervention, not over a live run, and in the directory the run
was started from. A redelivered intervention must not start a second child,
and the launcher must know which events come from the child it started.

## Changes in Detail

- `resume_records.clj` keeps what the launcher needs on disk:
  - A run's origin (`origin.edn` beside its events), recorded when the run
    registers for control (PR 4).
  - The latest launch per workflow in
    `<events>/operator/.resume-launches/<workflow>.edn`: intervention, run id,
    pid and pid start instant. It is written before and after the spawn.
  - Start evidence: an event under the run id that carries the intervention
    id as its `:workflow-run/correlation-id`.
- `resume_launcher.clj`:
  - `:launch!` runs in the consumer's pass and only spawns. A redelivered
    intervention gets its recorded launch back and no second child.
  - A retry is refused while another launch of the workflow is running. It is
    also refused while the workflow has a live runner here or a live manifest
    owner, and when its origin is unknown.
  - The child is `mf resume <id> --run-id <uuid> --correlation-id
    <intervention-id>`, started in the run's origin, with output in
    `<home>/logs/resume-<run-id>.log`. It runs under `nohup` (and `setsid`
    where installed) as an asynchronous `/bin/sh` command, so a terminal Ctrl-C
    or hangup does not reach it.
  - The command is `MINIFORGE_CMD` when set, else this process's command line
    minus its CLI arguments. If neither is known, there is no launcher.
  - `:await-start!` runs off the pass. It waits up to 60 s from the launch for
    the start evidence; another child's events do not count. A child that
    exits first did not start. A child still silent at the deadline is killed.
    An interrupted wait leaves the child running and reports it unverified.

## Testing Plan

- Records: origin, the record before and after the spawn, a recycled pid, live
  targets, and evidence from this intervention only.
- Launcher: argv, a single launch in the origin, redelivery, in-flight and
  live-target refusal, unknown origin, and a real detached spawn surviving
  SIGHUP.
- Start wait: another child's event, timeout kill, exit, and interruption.
- Pre-commit hook per commit.

## Deployment Plan

Nothing is registered until PR 4. Runs started before PR 4 have no recorded
origin, so a retry of them is refused `:resume-origin-unknown`.

## Known Limits

- A Babashka runner in another process keeps no manifest, so it is not seen as
  a live target.
- A child that starts just after the last check before the deadline is killed
  and reported as not started.

## Related Issues/PRs

Stack: `feat/resume-flags`, `feat/operator-async-resume`, this PR,
`feat/shared-process-handles`, `feat/operator-serve`.

## Checklist

- [x] Tests for new behaviour
- [x] Messages in the CLI system catalog
- [ ] Review comments addressed; CI green
