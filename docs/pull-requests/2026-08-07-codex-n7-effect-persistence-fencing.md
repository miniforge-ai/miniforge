<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: fence durable effect persistence

## Overview

Makes effect proposal publication create-only and lifecycle transitions atomic.
Concurrent processes coordinate below effect orchestration through a durable,
exact-record compare-and-set boundary.

## Layer

Foundations — durable effect identity and lifecycle persistence.

## Depends on

- #1697 (N7 grant issuance contract reconciliation) — merged
- #1698 (zero-warning Polylith baseline) — merged

## Strata Affected

- Persistence — immutable publication, atomic replacement, and per-effect lock.
- Store — transaction-level create and compare-and-set transitions.
- Record — caller-preallocated identity and exact lifecycle advancement.
- Tests — create-only, stale-transition, and storage-failure coverage.

## Motivation

A grant cannot be fenced to one irreversible effect if a duplicate proposal can
replace the first record or two processes can both advance the same stale value.
The storage boundary must make those operations indivisible before commit-time
authorization and execution rely on them.

## Changes in Detail

- Persist caller-preallocated effect IDs with create-only publication.
- Refuse duplicate IDs without replacing the original proposal.
- Serialize per-effect transitions and compare the exact durable record.
- Return read, write, and lock failures as localized anomaly values.
- Remove the public raw-write escape hatch.

## Testing Plan

- Test duplicate proposal refusal without replacement.
- Test stale compare-and-set refusal.
- Test corrupted records and storage failures remain data-valued.
- Run effect-transaction tests in every composing project.
- Run Poly, Kondo, stratum lint, standards review, and full pre-commit.

## Deployment Plan

Merge to `main`; existing records remain readable and require no migration.

## Related Issues/PRs

- Prepares the follow-up commit-authorization and reconciliation fencing PR.
- Blocks the N7 merge and deployment grant-enforcement work specs.

## Checklist

- [x] Proposal identity is caller-preallocated and create-only
- [x] Duplicate IDs preserve the original transaction
- [x] Lifecycle transitions use exact durable compare-and-set
- [x] Storage failures are returned as anomaly values
- [x] Poly reports zero errors and warnings
- [x] Standards and pre-commit gates pass
