<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: return EDGAR connector handle anomalies

## Overview

Migrates EDGAR connector handle lookup away from the shared throwing validation
helper.

## Motivation

Missing connector handles are ordinary connector protocol failures. EDGAR now
returns an anomaly value for that case instead of routing through an
`ExceptionInfo` throw.

## Base Branch

`main`

## Layer

Connector implementation refactor.

## What Changed

- Adds a direct `ai.miniforge/anomaly` dependency to `connector-edgar`.
- Changes the private handle lookup helper to call `connector/require-handle`.
- Makes `do-discover` and `do-extract` return response-shaped
  missing-handle anomalies.
- Updates EDGAR connector tests from thrown `ExceptionInfo` assertions to
  returned anomaly assertions.

The returned maps use `response/make-anomaly` so existing connector consumers
that dispatch with `response/anomaly-map?` continue to treat failures as
failures.

## Testing

- Focused EDGAR connector tests pass.
- Full `bb pre-commit` pass required before merge.
