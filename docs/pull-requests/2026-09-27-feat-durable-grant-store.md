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

Local results: 53 tests and 301 assertions pass in each of Miniforge, Core
and TUI. The store adds 13 tests and 65 assertions. The component standards
scan reports zero findings across 23 files.

## Adversarial review

Registration encodes and round-trips the record before creating any file.
It writes a unique temporary file, then publishes a hard link that cannot
replace an existing record. Both competing writers and duplicate calls are
tested. Revocation uses the same publication path, with a closed marker schema
that cannot change scope or grant identity.

Lookup reads and validates the issued record before applying the marker.
It distinguishes absent files from failed reads and rejects trailing EDN,
corrupt values, wrong IDs and marker fields that would widen authority.
The tests pin preservation of the original issuance bytes and first revocation.
The clock still controls expiry through the existing authorization function.

Source dependencies stay inside execution-grant or use component interfaces.
The per-file strata separate wire conversion, file-boundary validation and
authority composition. No generic replacement API or revocation removal exists.

## Deployment plan

No existing caller changes. The OPSV coordinator will register runtime-issued
grants and use current lookup. The store is trusted local authority state,
not an authentication boundary against a user who can edit its files.

## Related work

N7 section 5.4, N10's Ariadne profile, and PRs #1910 and #1927.

## Checklist

- [x] Implement and verify durable authority storage.
- [x] Complete adversarial standards review.
- [ ] Settle review comments and pass CI before merge.
