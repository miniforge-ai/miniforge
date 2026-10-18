<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# fix: grant authority read/publication race

## Overview and motivation

CI for #1956 exposed a concurrent revocation returning a read fault. An open may
observe an absent marker, followed by an existence check that sees a newly
published marker. Treat that interleaving as a bounded retry, not corrupt authority.

## Changes and standards

Retry only the missing-file/open race, once. Keep safe-path, schema, UTF-8, and
single-form validation on the retry. Malformed, linked, or persistently unreadable
records remain fail-closed. Do not weaken the concurrency assertion.

## Testing plan

The deterministic interleaving regression failed against the old reader and passed
with the bounded retry. All four component consumers passed (66 tests and 407
assertions per project), including after the standards-driven extraction of record
validation. The built CLI passed the store and race suites (24 tests, 147 assertions).
Run normal hooks, standards, review, and CI before merge. All JVM jobs are serialized
across sibling worktrees.

## Deployment and related work

Independent main-targeted CI fix; integrate into the pending feature stack.
No authority records or stored grants are modified by this task.

## Checklist

- [x] Deterministic regression fails before and passes after the fix
- [ ] Serial suites, review, and CI settle
