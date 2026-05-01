<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# feat: formalize workflow supervision and intervention lifecycle

## Overview

Add the first formal runtime pieces for live workflow governance:

- a per-run workflow supervision FSM
- a bounded intervention lifecycle model
- supervisory-state projection support for interventions

This slice keeps the orchestrator/application boundary stable while moving the
live supervision lane onto explicit state-machine semantics and evented
projection paths.

## Motivation

The architecture/spec work established that live supervision is separate from
both workflow execution and the learning loop. The codebase still needed a
formal runtime model for:

- per-run supervision posture
- bounded intervention intent and lifecycle
- supervisory projections for those interventions

Without that, live control remained too implicit and too hard to reason about.

## Changes In Detail

- Add `workflow.supervision`
  - formal per-run supervision states:
    - `:nominal`
    - `:warning`
    - `:paused-by-supervisor`
    - `:awaiting-operator`
    - `:halted`
  - move the supervision machine definition into
    `config/workflow/supervision.edn`
  - expose both the compiled FSM path and a small legacy-style helper API
- Add `operator.intervention`
  - bounded intervention vocabulary
  - intervention target-type resolution
  - intervention lifecycle transitions:
    - `:proposed`
    - `:pending-human`
    - `:approved`
    - `:rejected`
    - `:dispatched`
    - `:applied`
    - `:verified`
    - `:failed`
  - move intervention lifecycle configuration into
    `config/operator/intervention.edn`
- Extend `supervisory-state`
  - accumulate intervention events into the entity table
  - emit intervention upserts alongside existing supervisory entities
  - cover the new reducer/emitter behavior with direct tests
- Extend `event-stream`
  - register the supervision/intervention event types
  - add schemas for intervention payloads
  - localize the new supervisory intervention event messages in
    `config/event-stream/messages/en-US.edn`
  - preserve `:phase/transition-request` on phase-completed events
  - keep `:phase/redirect-to` as a derived compatibility projection for older
    consumers while the new transition-request shape remains authoritative
- Localize new runtime failure messages
  - operator intervention validation/transition errors now come from
    `config/operator/messages/en-US.edn`
  - workflow supervision transition errors now come from
    `config/workflow/messages/en-US.edn`
- Extend workflow interface surface
  - export supervision helpers without reintroducing `meta` terminology

## Testing Plan

- `clj-kondo --lint` on touched source and test files
- `bb test components/event-stream components/operator components/supervisory-state components/workflow`
- `bb pre-commit`

## Deployment Plan

No deployment step. Internal control-plane and projection refactor only.

## Related Issues/PRs

- Follows merged `#641`, `#642`, and `#644`
- Unblocks the next runtime slices:
  - orchestrator integration over supervision/intervention events
  - PR lifecycle FSM
  - configurable workflow compiler/selection work

## Checklist

- [x] Workflow supervision has a formal per-run FSM
- [x] Intervention intent/lifecycle is bounded and explicit
- [x] New configuration in this slice is loaded from EDN resources
- [x] New user-facing messages in this slice are localized through `en-US.edn`
- [x] Supervisory-state projects intervention entities
- [x] Event-stream carries the authoritative transition-request payload shape
- [x] Tests cover the new FSM/projection paths
