<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: consume operator interventions with no run active

## Overview

Last of six stacked PRs. Adds `mf operator serve`, a long-lived headless
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
| A workflow runner (`mf run`, chain, plan executor, `mf resume`) | one per process, started with the first registered run, stopped at exit | its own runs' pause/resume/cancel; process-global verbs except retries |
| `mf operator serve` | one per process, stopped on SIGINT/SIGTERM | process-global verbs, retries included; never another process's pause/resume/cancel |

Both go through `workflow-runner.control`: the same meta-loop context,
degradation manager, resume launcher, policy evaluator and consumer options,
except the ownership predicate. A runner's consumer declines retries, and
decisions on parked retries. A runner exits when its run does, and a retry it
launched would lose its verification. With no server running, a retry request
is not picked up, which the console shows. Passes are serialized across
processes by `<events>/operator/.consumer.lock`.

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
  - `<home>` is `MINIFORGE_HOME`, else `~/.miniforge`: it is computed as the
    parent of the events directory the consumer reads, so the lock, the
    discovery file and the `operator-dir` it names cannot point at different
    homes. The launch records are under that events directory too, and a
    retried child inherits `MINIFORGE_HOME`. Retry logs and pid files are
    in the CLI's logs directory, which is the same home only when
    `MINIFORGE_HOME` is set or the app profile's home is `~/.miniforge` (see
    Known Limits).
  - On start it hands every launch record never settled back to the
    verification pool. A server stopped or killed while a retry was starting
    therefore finishes verifying it on its next start.
- `mf operator` is its own group: `control-plane` is the dashboard's HTTP
  client.

## Testing Plan

- Serve lifecycle: start, ready line, discovery file, refusal of a second
  server, clean stop, lock reuse.
- Serve with its real consumer: the discovery file names the operator
  directory the consumer reads; an acknowledge request written with no run
  active reaches `verified`, and the stop the shutdown hook runs stops it.
- One home: with `MINIFORGE_HOME` at a temp directory, the lock, discovery
  file, operator directory, consumer events root, launch records and the
  retry log directory all resolve under it. The test sets it; without it
  the claim holds only under the default profile.
- Wiring: a runner's consumer declines retries, the server's takes them, and a
  starting server resumes pending launches.
- Smoke against a temp `MINIFORGE_HOME`: a retry of a run with a recorded origin
  reached `verified`, with the child running in the origin directory. A run
  without one failed `:resume-origin-unknown`. After the cursor was deleted,
  the redelivered retry reached `verified` again with the one child it had.
  With a child slow to start, the server was killed mid-wait, and the retry
  stayed `:dispatched`. On restart the same child's run was verified and its
  launch record settled.
- Pre-commit hook per commit.

## Deployment Plan

No migration. The console can start `mf operator serve` and read the ready line
or `<home>/operator-serve.json`.

## Known Limits

- Safe-mode applies to the degradation manager of the process that claims
  it. This is unchanged across processes.
- One home only when `MINIFORGE_HOME` is set, or under the default profile.
  Under an app profile with a home of its own (`miniforge-core`'s
  `.miniforge-core`) and no `MINIFORGE_HOME`, the event stream, and so this
  server's lock, discovery file, operator directory and launch records, use
  `~/.miniforge`. The CLI's own directories use the profile's home: retry
  logs and pid files (`logs-dir`), policy packs, and `mf resume`'s event
  reads. A retried child then reads the run's history from the wrong root
  and exits, and the retry fails `:resume-not-started`. This split predates
  the stack and is not fixed here; set `MINIFORGE_HOME` when serving under
  such a profile.
- The console does not yet link a retried attempt to the run it retried
  (see `fix/resume-launcher-review`).

## Related Issues/PRs

Stack: `feat/resume-flags`, `feat/operator-async-resume`,
`feat/resume-launcher`, `fix/resume-launcher-review`,
`feat/resume-launcher-hardening`, `feat/shared-process-handles`, this PR.

## Checklist

- [x] Tests for new behaviour
- [x] Messages in the CLI catalog
- [ ] Review comments addressed; CI green
