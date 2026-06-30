# refactor: return Excel connector handle anomalies

## Overview

Migrates the Excel connector away from the shared throwing handle lookup helper.

## Motivation

Excel still used a throwing helper for missing connector handles. Connector
protocol failures should be returned as anomaly values, with exception
conversion left to an outer boundary.

## Base Branch

`main`

## Layer

Connector implementation refactor.

## What Changed

- Adds a direct `ai.miniforge/anomaly` dependency to `connector-excel`.
- Changes the private handle lookup helper to call `connector/require-handle`.
- Makes `do-discover` and `do-extract` return response-shaped missing-handle
  anomalies.
- Updates Excel connector tests from thrown `ExceptionInfo` assertions to
  returned anomaly assertions.

The returned connector-boundary maps use `response/make-anomaly` so existing
consumers that dispatch with `response/anomaly-map?` continue to treat failures
as failures.

## Testing

- Focused Excel connector tests pass.
- Full `bb pre-commit` pass required before merge.

## Follow-Up

The shared throwing validation helpers remain until the remaining connector
implementations are migrated in subsequent slices.
