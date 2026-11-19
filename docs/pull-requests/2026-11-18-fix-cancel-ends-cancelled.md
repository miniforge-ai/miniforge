<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(workflow): a confirmed cancel ends the run cancelled, not failed

## Overview

An operator cancel now ends the run `:cancelled`, and the run reports it with
`:workflow/cancelled` naming who cancelled it and from where. Until now every stop path marked the
run `:failed`, so the run's status, its terminal event, the critical attention event and the
success-rate metric all counted a cancel as a failure.

## Motivation

1. N2 §2.2 lists `:cancelled` as a terminal state, and N3 says a cancel is reported by
   `:workflow/cancelled` alone and that consumers counting failures must not count it.
2. The issue 2377 acceptance rerun on c93b2df43 showed the gap: the cancel was applied and
   verified and the agent's process tree stopped, and the run still ended
   `Workflow failed: Workflow stopped by control plane` with `Attention critical: Workflow failed`.
3. The base PR records who cancelled and builds the event. This PR makes the runner and the CLI
   use it.

## Changes in Detail

1. `context/transition-to-cancelled` ends a run `:cancelled` through the machine's `:cancel`
   transition, with the same ended-at and duration stamps as the other terminal transitions, and
   records who cancelled it as `:execution/cancellation`. A run that completed or failed before
   the stop keeps its outcome. `context/cancellation` reads the record, falling back to the run's
   control state, then to an explicit unrecorded requester (`"unknown"` from `:api`) for a cancel
   set with no record, which no production path does. A resumed attempt drops the old record.
2. Every stop path uses it: the stop at a phase boundary (`runner_iteration`), a stop anomaly
   thrown out of the pipeline loop (`runner_execution_boundary`), and a DAG the operator stopped
   (`execution_dag`).
3. A phase that fails after its run was stopped sends `:cancel` to the machine instead of
   `:phase/fail` (`execution_transition/stop-aware-event`). That failure is the cancel's own
   effect, the agent call it cut short, and `:failed` is final, so without this the stop could not
   be recorded after it. This is the path the 2377 rerun took.
4. The run loop treats `:cancelled` as terminal (`runner_outcome/terminal-state?`).
5. The runner publishes `:workflow/cancelled` for a cancelled run, with `:workflow-run/status
   :cancelled`, and no `:pr/created` events. So does the CLI's own completion event, which is the
   terminal event for a spec run (`lifecycle/publish-completion-event`). `workflow/cancellation`
   is on the workflow interface for it.
6. The CLI shows `⏹ Workflow cancelled` in the summary and for a `:workflow/cancelled` event line.
   The manifest is marked `:cancelled`. A cancelled spec stays in `work/in-progress`, since it
   neither finished nor failed. A cancelled run is not recorded in the meta-loop's outcome tally.
7. Resume treats a `:cancelled` checkpoint like a `:failed` one (N2 §8.3): a new attempt starts
   from the trimmed pipeline, never the terminal snapshot.

## Testing Plan

1. New `cancelled_disposition_test.clj`:
   1. a phase that fails after the operator cancelled it mid-phase ends the run `:cancelled` with
      the requester recorded;
   2. `stop-aware-event` changes only a phase failure, and only on a stopped run;
   3. a stop does not rewrite a completed or failed run;
   4. a cancelled run publishes `:workflow/cancelled` alone.
2. New `cancelled_result_test.clj`: the CLI's completion event, summary line and kanban folder for
   a cancelled run.
3. Updated: `runner_test` (a stopped run returns `:cancelled` with its requester),
   `runner_iteration_control_test`, `dag_operator_control_test`, and `resume_test` (a cancelled
   checkpoint resumes as a new attempt).
4. The 42 component and base test namespaces that cover stops, cancels and the changed functions
   pass except `runner_checkpoint_test`'s relative-root case, which fails the same way on main on
   macOS. The 21 project test namespaces that mention cancel pass, among them
   `cancel_reaches_llm_test` and `dag_merge_cancel_test`: 161 tests, 4706 assertions.

## Notes

1. The CLI still exits 1 for a cancelled run, as for a failed one.
2. `configurable.clj` (6 layers) and `workflow_resume/core.clj` (10 layers) fail the 3-layer
   check, so the hook blocks any edit to them until they are split. Configurable workflows still
   end a stopped DAG `:failed`. Resume's reconstruction is unchanged; the CLI decides from the
   snapshot's status instead.
3. Readers that do not know `:workflow/cancelled` yet, the PR responder, the dashboard's run
   status and archive, and the event timeline, are fixed in their own PRs, which merge before this
   one.

## Deployment Plan

No configuration or data migration. Runs cancelled before this change stay recorded as failed.

## Related Issues/PRs

1. miniforge-ai/miniforge#2377: the cancel acceptance issue.
2. miniforge-ai/miniforge#2544: the base PR, which records who cancelled and builds the event.
3. N2 §2.2 and §8.3, N3 `workflow/cancelled`.

## Checklist

1. [x] Regression tests fail on the previous code
2. [ ] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
