<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor: return File connector handle anomalies

## Overview

Migrates File connector handle lookup away from the shared throwing validation
helper.

## Motivation

Missing connector handles are connector protocol failures. File connector source
and sink paths should return anomaly values for that case instead of throwing an
`ExceptionInfo` from a shared helper.

## Base Branch

`main`

## Layer

Connector implementation refactor.

## What Changed

- Adds a direct `ai.miniforge/anomaly` dependency to `connector-file`.
- Changes the private handle lookup helper to call `connector/require-handle`.
- Makes `do-discover`, `do-extract`, and `do-publish` return response-shaped
  missing-handle anomalies.
- Updates File connector tests from thrown `ExceptionInfo` assertions to
  returned anomaly assertions.

The returned maps use `response/make-anomaly` so existing connector consumers
that dispatch with `response/anomaly-map?` continue to treat failures as
failures.

## Testing

- Focused File connector tests pass.
- Full `bb pre-commit` pass required before merge.

## Follow-Up

The shared throwing validation helpers remain until the remaining connector
implementations are migrated in subsequent slices.
