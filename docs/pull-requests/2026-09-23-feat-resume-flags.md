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

- `--from-phase <p>` drops the FSM snapshot and keeps only the completed phases
  before `p`, the rewind the operator's `:retry-from-phase` plan describes. A
  phase the run never recorded is refused.
- `--run-id <uuid>` names the run a snapshot-less resume executes under. A
  value that is not a UUID is refused, and so is one that disagrees with the
  restored snapshot's id. Each case has its own message.
- `--correlation-id <uuid>` is stamped on the run's lifecycle events as
  `:workflow-run/correlation-id`. Without it, the snapshot's own correlation
  id is kept.
- The resumed run id now reaches `run-pipeline`. Before this, a resume without
  a snapshot announced and registered one id and ran under another.

## Testing Plan

- `resume-test`: rewind, unknown phase, run id adoption and both refusals, and
  correlation id pass-through and preservation.
- `runner-test`: a run's started event carries the correlation id its caller
  passed, the evidence PR 3's launcher waits for.
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
