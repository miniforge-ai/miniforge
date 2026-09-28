<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Persist runtime grant authority and revocation

## Overview

Add a class-1 runtime grant store with create-only issuance records and durable
revocation markers. Current lookup composes both records and fails closed when
either is unreadable.

## Motivation

OPSV needs current authority at execution time, including after a restart.
Returning a captured grant from a lookup function does not meet that contract.

## Layer and dependencies

Foundation, independent of PR #1927. The OPSV application layer will compose
this store with that PR's current-authority commit boundary.

## Changes in detail

- Register issued grants without replacing an existing identity.
- Read current authority by UUID, including durable revocation state.
- Persist the first revocation without rewriting or widening the issued grant.
- Reject malformed input, corrupt storage and mismatched record identities.

## Testing plan

Test initial lookup, durable reload, duplicate registration, revocation,
repeated revocation, corrupt records and malformed boundary inputs.
Run component tests, standards checks, lint, hooks and CI.

## Deployment plan

No existing caller changes. The OPSV coordinator will register runtime-issued
grants and use current lookup. The store is trusted local authority state,
not an authentication boundary against a user who can edit its files.

## Related work

N7 section 5.4, N10's Ariadne profile, and PRs #1910 and #1927.

## Checklist

- [ ] Implement and verify durable authority storage.
- [ ] Complete adversarial standards review.
- [ ] Settle review comments and pass CI before merge.
