<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# fix: tighten phase outcome standards follow-up

## Overview

Apply the review-driven cleanup that should have landed after `#639`:
remove duplicated failure/redirect construction, use shared phase helpers
more consistently in tests, and keep the event-stream tests deterministic.

## Motivation

`#639` landed the phase transition-request contract, but the review found a
few standards gaps in the follow-up delta:

- duplicated failure/redirect construction in phase implementations
- tests reaching into raw status fields where predicates/factories fit better
- a deterministic file-sink test relying on filename ordering

This PR is the standards cleanup that should have followed that merge.

## Changes In Detail

- Add shared phase helpers in `phase.phase-result`
  - `fail-phase`
  - `fail-and-request-redirect`
- Re-export those helpers in `phase.interface`
- Refactor `implement`, `review`, `verify`, and `release` error paths to use
  the shared helpers instead of duplicating failure/redirect map construction
- Update tests to prefer shared predicates/factories
  - use `phase/failed?` and `phase/succeeded?`
  - use `phase/request-redirect` in event-stream tests
- Keep the file-sink test order-independent by asserting on event types rather
  than directory listing order

## Testing Plan

- `clj-kondo --lint` on the touched source and test files
- `bb test components/phase-software-factory components/workflow components/event-stream`
- `bb pre-commit`

## Deployment Plan

No deployment step. This is a standards follow-up on top of the merged phase
outcome refactor.

## Related Issues/PRs

- Follows merged `#639`

## Checklist

- [x] Duplicate failure/redirect construction removed
- [x] Tests use shared predicates/factories where appropriate
- [x] Event-stream sink test no longer depends on file ordering
- [x] `bb pre-commit` passes
