<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli): workflow cancel reports whether the run applied it

## Overview

`mf workflow cancel <id>` printed "Cancel signal sent to workflow" and exited 0
as soon as the request file was written. It now waits up to 10 seconds for the
process running that workflow to record applying the cancel, and reports what
happened.

## Motivation

Issue #2377: the command exited 0 with "Cancel signal sent", but the request was
never consumed. It stayed `proposed`, and the agent's command finished and wrote
its file. Writing the request only queues it in `{events-dir}/operator/`. The
operator consumer in the process running that workflow applies it, and only for
a workflow registered in that process. A wrong id, an ended run, or another
`MINIFORGE_HOME` leaves the request queued indefinitely, with nothing to tell
the operator.

## Changes in Detail

1. New `cli.main.commands.intervention-outcome`: reads the lifecycle events
   (`:supervisory/intervention-state-changed`) of one intervention from the
   workflow's event directory. Only files modified since the request are
   parsed. `await-transition` polls until a terminal state or a deadline.
2. `workflow-cancel-cmd` resolves the events directory once and uses it for both
   writing the request and reading its lifecycle. After writing, it prints that
   the cancel was requested, then waits:
   1. `:verified` (the run's stop flag reads back as set): "Cancel applied",
      exit 0.
   2. `:failed` or `:rejected`: the run could not apply or verify the cancel,
      with the reason, exit 1. It does not claim the cancel had no effect: the
      stop flag can be set before a later step fails.
   3. Nothing terminal within the wait: the outcome is unknown, exit 1. The
      request may still be queued, or a run with no file sink may have applied
      it without a record this command can read.
3. Messages through the CLI catalog: `cancel-success` is replaced by
   `cancel-requested`, `cancel-applied`, `cancel-failed-lifecycle` and
   `cancel-outcome-unknown`. The help summary says the command waits.

## Testing Plan

New `bases/cli/test/ai/miniforge/cli/main/commands/workflow_cancel_outcome_test.clj`
runs a real operator consumer for a registered run, with the run's events in a
temp events directory:

1. The run applies the cancel: the run's stop flag is set, the output says
   "Cancel applied", and there is no exit call.
2. An id no running workflow owns: "Cancel outcome unknown", exit 1.
3. Applying throws after the stop flag is set. The flag reads back set, and the
   command says it "could not apply or verify" the cancel, exit 1.

All three fail against main's command (run with only the wait-time override
removed, since main has no wait). The existing request-shape test stubs the
wait. The workflow-commands, messages and help-example tests pass.

## Deployment Plan

No migration. Scripts that treated exit 0 from `workflow cancel` as success now
get exit 1 when the cancel was not confirmed.

## Related Issues/PRs

Part of #2377. #2384 makes a spec run execute under the id it registers, so the
id in its checkpoint directory is one a cancel reaches. #2277 and #2294 make an
applied cancel interrupt the running phase and the agent CLI's process tree.

## Notes

A run whose event stream has no file sink records no lifecycle on disk.
The command then reports the cancel as not confirmed even if it was applied.

## Checklist

- [x] Regression tests fail on main and pass with the change
- [ ] Adversarial review and standards review
- [ ] CI green, Copilot threads answered and resolved
