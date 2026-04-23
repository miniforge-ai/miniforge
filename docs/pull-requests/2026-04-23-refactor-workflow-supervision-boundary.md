# refactor: extract workflow supervision boundary

## Overview

Rename the workflow runtime's live monitoring boundary from `meta` to
`supervision` without changing the underlying behavior. This keeps the
workflow runner aligned with the merged architecture/spec direction while the
existing coordinator implementation remains in place underneath.

## Motivation

The workflow runtime was still wired through `meta` terminology:

- `:execution/meta-coordinator`
- `workflow.monitoring/create-meta-agents`
- `workflow.monitoring/check-workflow-health`
- `workflow.monitoring/handle-meta-agent-halt`

That conflated live workflow supervision with the learning/meta loop. This PR
separates the runtime boundary first, without trying to move the underlying
implementation in the same slice.

## Changes In Detail

- Add a workflow-facing supervision interface in
  `agent.interface.supervision`
- Re-export supervision operations from `agent.interface`
  - `create-supervision-coordinator`
  - `check-all-supervisors`
  - `reset-all-supervisors!`
  - `get-supervision-check-history`
  - `get-supervisor-stats`
- Refactor `workflow.context` to initialize and store
  `:execution/supervision-runtime`
- Refactor `workflow.monitoring` to expose supervision-facing helpers
  - `create-supervisors`
  - `build-supervision-state`
  - `check-workflow-supervision`
  - `handle-supervision-halt`
- Keep the underlying coordinator implementation unchanged by adapting the
  boundary over the existing meta-coordinator
- Translate workflow halt reporting to supervision terminology
  - `:supervision-halt`
  - `:anomalies.workflow/halted-by-supervision`
- Add workflow tests for:
  - context initialization with `:execution/supervision-runtime`
  - supervision halt transitioning an iteration to failed

## Testing Plan

- `clj-kondo --lint` on touched source and test files
- `bb test components/agent components/workflow`
- `bb pre-commit`

## Deployment Plan

No deployment step. This is a runtime-boundary refactor inside the workflow
and agent components.

## Related Issues/PRs

- Follows merged `#640`
- Prepares the next slices:
  - supervision FSM / intervention lifecycle
  - learning-loop ownership cleanup
  - workflow machine snapshot persistence/resume

## Checklist

- [x] Workflow runtime no longer uses `meta` terminology for live supervision
- [x] Underlying behavior preserved
- [x] Halt path covered by tests
- [x] `bb pre-commit` passes
