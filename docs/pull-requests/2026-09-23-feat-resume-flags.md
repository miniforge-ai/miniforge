<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: mf resume takes --run-id, --from-phase and --correlation-id

## Overview

First of six stacked PRs that let the operator console retry runs and run
interventions with no workflow active. This one gives `mf resume` the inputs
the operator's resume launcher (PR 3) passes to the process it starts.

## Motivation

The launcher starts a retried run as its own `mf resume` process. It must name
the run id before the spawn, ask for a rewind to a phase, and recognise the
events of the child it started. `mf resume` accepted none of these.

## Changes in Detail

- `--from-phase <p>` keeps only the completed phases before `p`, the rewind
  the operator's `:retry-from-phase` plan describes. A phase the run never
  recorded is refused.
- The rewind drops the FSM snapshot, which is parked after `p`. The re-run
  keeps the run's input and acting authority from it, and starts a fresh
  machine at `p` holding only the results of the phases before `p`: a rewind
  to implement sees the plan result, and implement and later phases start
  clean. Output the snapshot does not tie to a phase (artifacts, a DAG's
  result and PR infos) is not carried.
- A fresh run context (`create-context`) now starts holding
  `:resume-phase-results`, so `run-pipeline` keeps them without a snapshot.
  Before this it dropped them, so the rewind lost the plan result.
- Only checkpointed phase results reach the run. A run with no checkpoint
  has only telemetry rebuilt from events (outcome, duration), which no phase
  can build on, so a resume of it passes none, as before this change.
- A rewind that would keep a phase with no checkpointed result is refused
  (`:anomalies/unsupported`, `:resume/reason :phase-results-not-checkpointed`,
  naming the phases) instead of running it without its inputs. A rewind to
  the first phase keeps nothing and always runs. The rule lives in the
  `workflow-resume` component (`rewind-refusal`, with `rewind-kept-phases`
  and `checkpointed-phase-results`), so the operator's `:retry-from-phase`
  (PR 2) refuses the same rewinds with the same reason.
- The workflow identity is resolved from the run as recorded, so a rewind of
  a run with no recorded spec still finds its workflow in the snapshot.
- A rewind also drops the old run's DAG tasks and artifacts. They are only
  used when the plan phase runs its DAG, where they would skip re-planned
  tasks that share an id.
- A rewind to `p` restores the latest workspace checkpoint made by a phase
  that stays completed, never one made by `p` or a later phase. When there is
  none, the run starts from a fresh workspace. Refusing instead would block
  every rewind to the first phase, and the recorded states after `p` are
  exactly what the rewind is meant to replace.
- `--run-id <uuid>` names the run a snapshot-less resume executes under. A
  value that is not a UUID is refused, and so is one that disagrees with the
  restored snapshot's id. Each case has its own message.
  (Changed by `fix/resume-launcher-review`: a `--run-id` beside a snapshot
  now runs the snapshot's state under that id, as a new attempt; one
  naming another existing run is refused. That PR also makes rewinds
  restore workspaces: the checkpoint's phase was read from the wrong key.)
- `--correlation-id <uuid>` is stamped on the run's lifecycle events as
  `:workflow-run/correlation-id`. Without it, none is imposed and the runner's
  default applies: the run's own id, as before this change.
- The options are checked before a completed run is reported done, so an
  invalid request is refused rather than answered "already completed".
- The resumed run id now reaches `run-pipeline`. Before this, a resume without
  a snapshot announced and registered one id and ran under another.

## Testing Plan

- `resume-test`: rewind (DAG state dropped, workspace from an earlier phase or
  none) and unknown phase.
- `resume-test`: run id adoption and both refusals, correlation id
  pass-through, and option checks on a completed run.
- `resume-test`: a rewind hands the runner the input, acting authority and
  only the earlier phases' results.
- `resume-test`: a rewound checkpoint-only run keeps its workflow identity;
  event telemetry never reaches the run; a rewind keeping a phase with no
  checkpointed result is refused with its reason and phases; an events-only
  rewind to the first phase runs.
- `rewind-test` (workflow-resume): kept phases, checkpointed results versus
  event telemetry, and the refusal with its reason and phases.
- `runner-test`: a run's started event carries the correlation id its caller
  passed, the evidence PR 3's launcher waits for.
- `runner-test`: a resume without a snapshot starts at the pipeline's first
  phase holding the seeded results.
- Pre-commit hook per commit.

## Deployment Plan

No migration. The flags are optional; existing invocations behave as before.

## Related Issues/PRs

Stack: this PR, then `feat/operator-async-resume`, `feat/resume-launcher`,
`feat/resume-launcher-hardening`, `feat/shared-process-handles`,
`feat/operator-serve`.

## Checklist

- [x] Tests for new behaviour
- [x] Messages in the CLI catalog
- [ ] Review comments addressed; CI green
