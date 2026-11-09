<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli): a spec run executes under the id it registers for cancel

## Overview

`mf run <spec>` registers its run id with the operator channel, but the
pipeline ran under a second, freshly minted id. The workflow context the
runner builds now carries the registered id, so the pipeline adopts it as
`:execution/id` and the run has one id.

## Motivation

Issue #2377: `mf workflow cancel <id>` with the id taken from the run's
checkpoint directory exited 0, but the request stayed `proposed`, the
agent's command finished, and its late write landed.

1. `run-workflow-from-spec!` mints a run id, registers it as a live runner,
   and publishes `workflow-started` under it.
2. `create-workflow-context` received that id but left it out of the context
   it returns. `run-pipeline` adopts `:workflow-id` from its options (#1441);
   without one it mints a new id. Checkpoints and phase and agent events were
   written under that second id. The CLI header names neither id.
3. The runner's operator consumer accepts a workflow cancel only for an id
   registered in its process. A cancel aimed at the checkpoint id is
   skipped on every poll and never recorded, so no lifecycle event is
   published and the request file stays `proposed` indefinitely.

The plan executor already worked around this by assoc'ing `:workflow-id`
after `create-workflow-context`; the resume command passes it explicitly.

## Changes in Detail

1. `cli.workflow-runner.context/create-workflow-context` returns the context
   with `:workflow-id`. The spec runner passes that context to
   `run-pipeline`, which adopts the id. The plan executor's own assoc sets the
   same value; chain steps already replace `:workflow-id` with a step id.

## Testing Plan

New `bases/cli/test/ai/miniforge/cli/workflow_runner/run_identity_test.clj`:

1. `the-workflow-context-carries-the-run-id-test`: the context names its run.
2. `a-spec-run-executes-under-its-registered-id-test`: drives
   `run-workflow-from-spec!` with collaborators stubbed and the real
   `create-workflow-context`; the id registered with the operator channel
   equals the `:workflow-id` the pipeline receives.

Both fail on main (`nil` instead of the id) and pass with the change. The
context, control-wiring, plan-executor pipeline, JSON-output, execution and
operator-wiring tests also pass.

## Deployment Plan

No configuration or data migration. Runs started before this change keep
their two directories.

## Related Issues/PRs

Part of #2377. #2277 makes a consumed cancel interrupt the running phase and
the agent CLI's process tree; #2294 does the same for parent merges. Neither
changes which id a spec run executes under.

## Checklist

- [x] Regression tests fail on main and pass with the change
- [ ] Adversarial review and standards review
- [ ] CI green, Copilot threads answered and resolved
