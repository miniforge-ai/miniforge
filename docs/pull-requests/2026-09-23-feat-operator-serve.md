<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: consume operator interventions with no run active

## Overview

Last of five stacked PRs. Adds `mf operator serve`, a long-lived headless
consumer of `<home>/events/operator/`.

## Motivation

The operator console (miniforge-control) writes intervention requests to
`~/.miniforge/events/operator/`. Before this, only a process running a workflow
(`mf run`, chain, plan executor, resume) consumed that directory. With no run
active, acknowledge, safe-mode, human-review, intervention decisions, retries,
and re-evaluations sat unread.

## Where the operator consumer runs

| Process | Consumer | Claims |
|---|---|---|
| A workflow runner (`mf run`, chain, plan executor, `mf resume`) | one per process, started with the first registered run, stopped at exit | its own runs' pause/resume/cancel; process-global verbs; retries |
| `mf operator serve` | one per process, stopped on SIGINT/SIGTERM | process-global verbs; retries; never another process's pause/resume/cancel |

Both go through `workflow-runner.control`: the same meta-loop context,
degradation manager, resume launcher, policy evaluator and consumer options.
Passes are serialized across processes by `<events>/operator/.consumer.lock`.

## Changes in Detail

- `mf operator serve` (`cli/main/commands/operator_serve.clj`):
  - Holds `<home>/operator-serve.lock` for the process lifetime. The OS
    releases it on any exit, including SIGKILL. A second server for the same
    home exits 1 and names the running pid.
  - Writes `<home>/operator-serve.json` (`pid`, `started`, `operator-dir`)
    atomically. A clean stop removes it. After a SIGKILL it remains, so
    readers must check the pid is alive.
  - Prints one stdout line once the consumer is polling: the same JSON plus
    `"ready": true`.
  - On SIGTERM or SIGINT a shutdown hook stops the consumer, which drains, then
    removes the discovery file and releases the lock. The process then exits
    143 (SIGTERM) or 130 (SIGINT); a console should treat those as a clean
    stop.
  - `<home>` is `MINIFORGE_HOME`, else `~/.miniforge`: the home the consumer's
    events directory is under.
- `mf operator` is its own group: `control-plane` is the dashboard's HTTP
  client.

## Testing Plan

- Serve lifecycle: start, ready line, discovery file, refusal of a second
  server, clean stop, lock reuse.
- Serve with its real consumer: an acknowledge request written with no run
  active reaches `verified`, and the stop the shutdown hook runs stops it.
- Smoke against a temp `MINIFORGE_HOME`: a retry of a run with a recorded origin
  reached `verified`, with the child running in the origin directory. A run
  without one failed `:resume-origin-unknown`. After the cursor was deleted,
  the redelivered retry reached `verified` again with the one child it had.
- Pre-commit hook per commit.

## Deployment Plan

No migration. The console can start `mf operator serve` and read the ready line
or `<home>/operator-serve.json`.

## Known Limits

Safe-mode applies to the degradation manager of the process that claims it.
This is unchanged across processes.

## Related Issues/PRs

Stack: `feat/resume-flags`, `feat/operator-async-resume`,
`feat/resume-launcher`, `feat/shared-process-handles`, this PR.

## Checklist

- [x] Tests for new behaviour
- [x] Messages in the CLI catalog
- [ ] Review comments addressed; CI green
