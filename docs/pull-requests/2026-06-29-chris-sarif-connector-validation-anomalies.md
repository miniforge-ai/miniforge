<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: return SARIF connector handle anomalies

## Overview

Migrates SARIF connector handle lookup away from the shared throwing validation
helper.

## Motivation

Missing connector handles are connector protocol failures. SARIF source and
checkpoint paths should return anomaly values for that case instead of throwing
an `ExceptionInfo` from a shared helper.

## Base Branch

`main`

## Layer

Connector implementation refactor.

## What Changed

- Adds a direct `ai.miniforge/anomaly` dependency to `connector-sarif`.
- Changes the private handle lookup helper to call `connector/require-handle`.
- Makes `do-discover`, `do-extract`, and `do-checkpoint` return
  response-shaped missing-handle anomalies.
- Updates SARIF connector tests from thrown `ExceptionInfo` assertions to
  returned anomaly assertions.

The returned maps use `response/make-anomaly` so existing connector consumers
that dispatch with `response/anomaly-map?` continue to treat failures as
failures.

## Testing

- Focused SARIF connector tests pass.
- Full `bb pre-commit` pass required before merge.

## Follow-Up

The shared throwing validation helpers remain until the shared connector API is
cleaned up in a final slice.
