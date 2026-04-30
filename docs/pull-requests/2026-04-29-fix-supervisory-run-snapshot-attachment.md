# fix/supervisory-run-snapshots

## Summary

Ensure workflow execution attaches supervisory-state to the active event stream
before lifecycle events are published, so running workflows emit fresh
`:supervisory/*` snapshot events for downstream clients.

The immediate user-visible symptom was that real workflows were running and
writing raw workflow events, but the TUI only showed old runs because no fresh
`:supervisory/workflow-upserted` events were being emitted on that execution
path.

## Why

`miniforge-control` reads supervisory snapshot entities, not raw workflow
events. We confirmed that:

- fresh raw `:workflow/*` and `:phase/*` events were present
- fresh `:supervisory/workflow-upserted` events were not
- the supervisory projector itself still worked when explicitly attached

That meant the failure mode was not in projection logic. It was that some
workflow execution paths were relying on callers to have already attached
supervisory-state to the stream.

This PR makes supervisory snapshot emission a workflow-runner guarantee instead
of a fragile caller obligation.

## Changes

- add idempotent supervisory attachment helpers in
  [core.clj](../../components/supervisory-state/src/ai/miniforge/supervisory_state/core.clj)
  - `attached?`
  - `ensure-attached!`
- re-export those helpers from
  [interface.clj](../../components/supervisory-state/src/ai/miniforge/supervisory_state/interface.clj)
- add `supervisory-state` as a workflow component dependency in
  [deps.edn](../../components/workflow/deps.edn)
- ensure `run-pipeline` auto-attaches supervisory-state whenever an
  `:event-stream` is supplied in
  [runner.clj](../../components/workflow/src/ai/miniforge/workflow/runner.clj)
- add regression coverage for:
  - idempotent supervisory attachment in
    [core_test.clj](../../components/supervisory-state/test/ai/miniforge/supervisory_state/core_test.clj)
  - workflow-runner auto-attachment and emitted
    `:supervisory/workflow-upserted` events in
    [runner_test.clj](../../components/workflow/test/ai/miniforge/workflow/runner_test.clj)

## Validation

- `clj-kondo --lint` on touched files
- `bb test components/supervisory-state components/workflow`

## Outcome

Workflow execution now emits fresh supervisory snapshots even when the caller
only provides an event stream. That restores the data path the TUI depends on
for live runs, agents, decisions, and correlated run context.
