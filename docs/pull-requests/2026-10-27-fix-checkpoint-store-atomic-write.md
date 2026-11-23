<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Preserve checkpoints when atomic replacement fails

## Overview and motivation

Finish existing PR #2039. Replace delete-then-move with one atomic replacement.
A failed rename must leave the previous checkpoint available for resume.

## Layer and dependencies

Workflow storage boundary, based on main. No outstanding prerequisite PRs.
The original author’s change is retained and aligned with current main.

## Changes

- Use Java atomic move with replacement, without deleting the target first.
- Test first creation, replacement, reopen, and a deterministic failed rename.
- Repair sentence-length lint failures in incoming documentation without changing contracts.

## Verification plan

The regression fails against the prior packaged CLI: failed rename deletes the
snapshot and prevents public checkpoint loading. Two assertions fail.
Run workflow consumers serially, rebuild the CLI, and repeat isolated package tests.
Require standards review, signed hooks, exact-head Copilot review, and all CI gates.

## Local results

All three workflow consumers passed serially (1m42s). The rebuilt CLI is
39,232,366 bytes. Isolated packaged checkpoint suites pass 12 tests and 50 assertions,
without production source overlays. New JVM tests take 54 ms and 8 ms;
both remain below the brick-test placement threshold.

Normal hooks passed after prose repairs, and the main-alignment commit is signed.
Signing required desktop-agent access outside the sandbox. The standards scan
reports no violations; the whole PR has 66 reportable code lines.
Exact-head review and remote CI remain required; no gate was bypassed.

## Scope and deployment

Normal CLI deployment; no checkpoint format migration.
This fix does not claim power-loss durability, concurrent-writer isolation,
transactional multi-file checkpoints, or complete N7 recovery.
Unique temporary paths, force ordering, and failure propagation need separate work.
An unsupported atomic move remains a failed write; there is no non-atomic fallback.

## Related work

- PR #2039: original atomic replacement fix.
- PR #2088: full OPSV execution against the current event journal.
