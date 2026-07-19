<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: return GitHub connector validation anomalies

## Overview

Migrates the GitHub connector away from the shared throwing validation helpers
for handle lookup and auth validation.

## Motivation

`connector-github` was still translating missing handles and malformed auth
through shared `!` helpers. Those are ordinary connector protocol failures and
should remain anomaly values until a true process boundary.

## Base Branch

`main`

## Layer

Connector implementation refactor.

## What Changed

- Adds a direct `ai.miniforge/anomaly` dependency to `connector-github`.
- Changes the private handle lookup helper to call `connector/require-handle`.
- Makes `do-discover` and `do-extract` return response-shaped
  missing-handle anomalies.
- Makes malformed auth return a localized validation anomaly from
  `do-connect`.
- Updates GitHub connector tests from thrown `ExceptionInfo` assertions to
  returned anomaly assertions.

The returned maps use `response/make-anomaly` so existing connector consumers
that dispatch with `response/anomaly-map?` continue to treat failures as
failures.

## Testing

- Focused GitHub connector tests pass.
- Full `bb pre-commit` pass required before merge.

## Follow-Up

The shared throwing validation helpers remain until the other connector
implementations are migrated in subsequent slices.
