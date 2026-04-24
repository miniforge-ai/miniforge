# feat: restore workflows from durable machine snapshots

## Overview

Persist the authoritative workflow execution machine as durable checkpoint data
and teach resume flows to restore from that snapshot instead of reconstructing
execution state from phase indexes alone.

## Motivation

`#638` made the execution machine authoritative for workflow progression, but
checkpoint/resume still depended on legacy event-log reconstruction and
phase-index semantics. The specs now require machine-snapshot restoration.

This slice closes that gap by making resume prefer a persisted execution
snapshot while preserving the existing event-history fallback.

## Changes In Detail

- Add durable workflow checkpoint storage in `workflow.checkpoint-store`
  - machine snapshot
  - checkpoint manifest
  - per-phase checkpoint files
- Add `workflow.interface/load-checkpoint-data` as the public restore boundary
- Extend `workflow.context` with `restore-context`
  - recompiles the machine
  - restores the persisted machine snapshot
  - restores phase results
  - rehydrates runtime-only monitoring fields
- Persist checkpoints from `workflow.runner`
  - after initial context creation
  - after each iteration result
- Update `workflow-resume` to prefer machine snapshots and checkpoint manifests
  over event-log reconstruction when available
- Update CLI `resume` to resume the original workflow run id and pass restored
  machine state into `workflow/run-pipeline`
- Add user config data for the default checkpoint root
- Normalize checkpoint payloads into EDN-readable data so timestamp fields
  round-trip cleanly

## Testing Plan

- `clj-kondo --lint` on all touched source and test files
- `bb test components/workflow-resume components/workflow components/config`
- `bb pre-commit`

## Deployment Plan

No deployment step. This is an internal workflow runtime change.

## Related Issues/PRs

- Follows `#638`, which made the execution machine authoritative
- Prepares the next workflow critical-path slices:
  - supervision/runtime boundary extraction
  - formal supervision FSM and intervention lifecycle
  - configurable workflow compilation

## Checklist

- [x] Execution machine snapshots persist durably
- [x] Resume prefers persisted machine snapshots
- [x] Legacy event-history fallback remains available
- [x] Checkpoint payloads are EDN-readable
- [x] Tests cover snapshot persistence and restoration
- [x] Full pre-commit passes
