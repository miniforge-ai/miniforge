<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: model activation-local phase progress

## Overview and motivation

Pure domain model for the approved N3 heartbeat contract. Scheduler activity must
not masquerade as work. Separate activation identity, coverage, tick attempts,
and substantive progress; derive durations only from caller-supplied monotonic time.

## Changes and boundaries

Build the value-level model before scheduler/observer wiring. Explicit progress
observations must match workflow, phase and activation, and advance an ordered
source position; repeated, stale or foreign observations do not reset stalls.
Unknown coverage stays explicit. No threads, clocks, publication or persistence.

## Testing and deployment

Deterministic tests cover repeated ticks, wall-clock jumps, progress, duplicates,
foreign activations, unknown coverage and re-entry. Normal size, standards,
signed hooks, review and CI gates apply. Integrate only after contract PR #2072
and typed heartbeat admission; no claim of runtime completion in this PR.

## Verification

Four consumer projects passed serially (2m15s). Rebuilt CLI: 39,204,230 bytes;
isolated package: five tests, 26 assertions, no failures or errors. Kondo and
inferred strata checks passed. No clock sleeps, threads or external services in
model tests. Main alignment adds the approved contract documentation only.
