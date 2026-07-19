<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Fix: Return cost breakdown validation failures as anomalies

## Overview

This PR converts `workflow.cost-breakdown/add-phase-cost` validation failures
from thrown exceptions to canonical anomaly values. Valid phase accumulation is
unchanged.

## Motivation

The exceptions-as-data cleanup scan reports one throw site in the workflow cost
breakdown helper. Unknown phases and invalid iteration counts are invalid
telemetry input and can be represented directly as anomaly data for callers to
fold into response chains.

## Changes in Detail

- Return `:anomalies/incorrect` anomaly maps for unknown cost phases.
- Return `:anomalies/incorrect` anomaly maps for positive iteration counts on
  non-iteration phases.
- Update focused workflow cost tests to assert returned anomaly data instead
  of caught exceptions.

## Testing Plan

- Run focused workflow cost breakdown tests.
  Latest result: 14 tests, 56 assertions, 0 failures.
- Run focused workflow anomaly tests for the cost contract.
  Latest result: 4 tests, 27 assertions, 0 failures.
- Run the exceptions-as-data scanner and confirm `workflow.cost-breakdown`
  contributes zero cleanup-needed rows.
  Latest scanner count: 155 cleanup-needed rows; `workflow.cost-breakdown`
  contributes zero rows.
- Run `bb pre-commit`.
  Latest result: all checks passed.

## Deployment Plan

No deployment steps. This is a pure helper validation cleanup.

## Related Issues/PRs

- Follows PR #1281.

## Checklist

- [x] Implementation updated.
- [x] Tests updated and focused suite passes.
- [x] `bb review` count reduced.
- [x] `bb pre-commit` passes.
- [ ] PR opened, comments resolved, CI green, and merged.
