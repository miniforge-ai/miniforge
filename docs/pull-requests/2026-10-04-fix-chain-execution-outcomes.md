<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: terminate chains on unsuccessful child outcomes

## Overview

Only completed child workflows may advance a chain. Loading and execution
failures produce terminal step and chain outcomes instead of escaping the loop.

## Motivation

The existing executor treats every result other than an explicit failure as
success, including nil, cancelled, running, and anomaly results. Missing workflow
exceptions escape after step-start without either terminal outcome. These paths
violate N2.CH.2 and N2.CH.3 and can execute dependent work incorrectly.

## Layer

Application execution, branched from main `1a9150be` and updated to `424f6359`
after chain definition selection PR #2009 merged.

## Changes in Detail

- Isolate pure binding interpretation and result construction.
- Convert legacy loading and child execution exceptions at a named boundary.
- Normalize incomplete and anomalous results to failure.
- Stop reduction before dependent steps after any unsuccessful child.
- Compose lifecycle effects around normalized child outcomes.
- Use the shared clock helper for non-negative elapsed durations.

## Testing Plan

The original executor produced 15 failures and one error in the first regressions.
Expanded focused tests pass 21 tests / 111 assertions, including existing binding,
correlation, lifecycle event, and projection compatibility tests. New coverage
includes returned loading anomalies, thrown child and binding exceptions, malformed
results, misleading completed-status fields, and backwards clock adjustments.
All three deployed workflow consumers pass serially (Miniforge, Core, and TUI).
The CLI rebuild succeeds. Kondo has zero warnings/errors, strata are truthful,
and the incremental standards scan finds zero violations across 4299 files.
The isolated packaged CLI passes all 21 tests / 111 assertions outside the
checkout, using only the artifact and test files, without source overlays.
The adversarial pass traced default inputs, exceptions, and misleading statuses;
six additional mixed-status regression failures were reproduced and corrected.

## Deployment Plan

Public result shapes and optional missing-binding behavior remain compatible.
Previously false-success paths now return failed chains and stop dependents.
This does not claim v2 event migration, run identity/snapshot recovery, required
binding schema admission, pause/resume support, or durable publication acknowledgment.
Publication failures still require the separate N3 runtime migration.

## Related Issues/PRs

Implements the child-failure subset of contract reconciliation PR #2000.

## Checklist

- [x] Reproduce failures and implement normalized child outcomes.
- [x] Validate deployed consumers and packaged artifact.
- [x] Complete adversarial standards review and signed hooks.
- Require clean exact-head review and every CI gate before merge.
