<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: Separate workflow event payloads and dispatch

## Overview and motivation

Workflow event publishing has five inferred strata. Separate transport dispatch,
phase diagnostics and completion PR projection before migrating lifecycle events
to current durable journals.

## Layer and dependencies

Internal workflow component decomposition on main. This prerequisite preserves
the current event publisher API and legacy publication behavior.

## Changes in detail

- Keep lifecycle orchestration in runner-events.
- Extract transport eligibility and delivery from payload construction.
- Isolate phase failure diagnostics and PR identity projection.
- Preserve event order, nil-stream behavior, inner failure precedence and
  first-seen PR deduplication.

## Testing plan

Run existing workflow event, phase outcome and runner consumers serially.
Add focused tests for payload fallback precedence and duplicate PR identities.
Verify inferred strata, lint, composition and pinned standards.

All three workflow consumer suites passed serially. The final isolated CLI package
passes 31 tests / 91 assertions across existing event tests, inner-result outcomes
and new projection/transport cases. Scoped lint has zero warnings/errors, inferred
strata stay within three layers, and the pinned scan reports zero violations.

The adversarial pass preserved explicit nil/zero values, diagnostic precedence,
inner failures, PR ordering and structured nonfatal exceptions. The transport
predicate is named stateful-stream?, not durable: dereferenceability proves no
persistence guarantee.

## Deployment plan

No host switch or new durability claim in this refactor. Follow-up producer and
host changes will establish current-journal acknowledgment.

## Checklist

- [x] Adversarial behavior and standards review
- [x] Serial tests and static checks
- [ ] Signed bounded commits and settled review
