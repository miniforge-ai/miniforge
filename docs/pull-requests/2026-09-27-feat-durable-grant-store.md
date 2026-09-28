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

Local results: 58 tests and 332 assertions pass in each of Miniforge, Core
and TUI. The store adds 18 tests and 96 assertions. The component standards
scan covers all source and test files, including the file-boundary helpers.

## Adversarial review

Registration encodes and round-trips the record before creating any file.
It writes and forces a unique temporary file, then publishes a hard link that
cannot replace an existing record. It forces the directory and its ancestors
before acknowledging publication. Both competing writers and duplicate calls are
tested. Revocation uses the same publication path, with a closed marker schema
that cannot change scope or grant identity.

Lookup reads and validates both records before applying the marker. An orphaned
marker without valid issuance is a storage fault, not clean absence.
It distinguishes absent files from failed reads and rejects trailing EDN,
corrupt values, wrong IDs and marker fields that would widen authority.
Symlinks are rejected without following their targets. Registration and reload
require pristine issuance records: both revocation fields must be nil.
Results normalize timestamps to Instant on the first call as well as reload.
The tests pin preservation of the original issuance bytes and first revocation.
Injected file and directory sync failures return faults. A sequential retry
of an existing revocation repeats durability barriers before acknowledging it.
The clock still controls expiry through the existing authorization function.

Source dependencies stay inside execution-grant or use component interfaces.
The per-file strata separate wire conversion, file-boundary validation and
authority composition. No generic replacement API or revocation removal exists.

## Deployment plan

No existing caller changes. The OPSV coordinator will register runtime-issued
grants and use current lookup. The store is trusted local authority state,
not an authentication boundary against a user who can edit its files.
Storage requires a filesystem supporting hard links and file/directory force.
Unsupported barriers fail closed; there is no fallback claiming durability.
After a post-publication failure, a record may be visible despite the fault.
The caller must treat that result as uncertain, not as successful persistence.

## Related work

N7 section 5.4, N10's Ariadne profile, and PRs #1910 and #1927.

## Checklist

- [x] Implement and verify durable authority storage.
- [x] Complete adversarial standards review.
- [ ] Settle review comments and pass CI before merge.
