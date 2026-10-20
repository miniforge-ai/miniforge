<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: preserve terminal publication receipts

## Overview and motivation

The workflow completion publisher discards its successful durable receipt while
emitting auxiliary PR events. Constructor exceptions also become warnings instead
of current-stream refusals. Preserve the terminal result so the runner can honor
it in the subsequent admission change.

## Scope and layers

Base: main `f4fa22290`. Application publication boundary. Align #2057's failure
diagnostic enrichment before opening this PR, preserving both changes.
Keep PR notifications outside the terminal receipt's failure boundary. Do not
publish PR notifications after refused completion. Fatal errors still propagate.
Legacy warning behavior and nil-stream no-op remain supported.

This does not yet change runner terminal state, checkpoint ordering or resume.
Those require a separate authoritative FSM/admission change.

## Testing plan

Real current journals: acknowledged completion, refusal, auxiliary constructor
failure, terminal constructor failure and reopen. Legacy and nil compatibility;
fatal error propagation. Serial workflow consumer tests, packaged CLI verification,
standards review, normal signed hooks, exact-head review and full CI before merge.

## Verification so far

- All three workflow product consumers pass serially (1m33s).
- CLI rebuild succeeds: 39,188,825 bytes. Isolated packaged verification passes
  38 tests / 127 assertions, with test-only support paths and no production overlay.
- The pre-fix package reproduced eight failures in the terminal receipt suite.
- Changed namespaces match inferred strata and have no kondo warnings/errors.
- Current legacy-form PR notifications are refused by the journal. The test
  asserts their attempted construction and preserved completion receipt, not a
  nonexistent recorded PR event. This migration is a separate follow-up.
- The initial package check lacked phase test support needed by the existing
  run7 suite; adding that test-only path resolves it. No production code masking.

## Deployment

Normal package rollout; no stored-data migration.
