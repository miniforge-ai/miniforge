<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat: normalize phase transition requests into explicit outcomes

## Overview

Remove phase-owned workflow steering via `:redirect-to` and replace it with
an explicit transition-request contract owned by the `phase` component.
Workflow execution now translates that semantic request into machine events.

## Motivation

`#638` made the execution machine authoritative for workflow progression, but
software-factory phases were still steering control flow directly by writing
`:redirect-to` into phase results. That left workflow semantics leaking upward
from phase implementations into the runner contract.

This slice makes the boundary explicit:

- phases request transitions semantically
- workflow execution translates requests into machine events
- event payloads publish the same request shape end-to-end

## Changes In Detail

- Add shared phase outcome helpers in `phase.phase-result`
  - `transition-request`
  - `transition-target`
  - `redirect-requested?`
  - `request-redirect`
- Re-export those helpers in `phase.interface`
- Refactor software-factory phases to request redirects explicitly
  - `implement`
  - `review`
  - `verify`
  - `release`
- Refactor `workflow.execution/determine-phase-event` to consume transition
  requests instead of reading `:redirect-to`
- Update workflow phase-completion event publishing to include
  `:phase/transition-request`
- Update `event-stream.core` so emitted events preserve the new request shape
- Update regression and phase-behavior tests to assert predicates/helpers
  instead of reaching into the old `:redirect-to` field

## Testing Plan

- `clj-kondo --lint` on all touched source and test files
- `bb test components/phase components/phase-software-factory components/workflow components/event-stream`
- `bb pre-commit`

## Deployment Plan

No deployment step. This is an internal contract cleanup within the workflow
execution stack.

## Related Issues/PRs

- Follows `#638`, which made the execution machine authoritative
- Prepares the next slices:
  - workflow snapshot persistence/resume from machine state
  - supervision boundary extraction from `meta` terminology
  - PR lifecycle FSM formalization

## Checklist

- [x] Phase redirect semantics moved into shared phase helpers
- [x] Workflow execution consumes explicit transition requests
- [x] Event payloads preserve transition requests
- [x] Redirect-specific tests updated to the new contract
- [x] Full pre-commit passes
