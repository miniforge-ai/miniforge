<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: return Jira connector validation anomalies

## Overview

Migrates the Jira connector away from shared throwing validation helpers for
missing handles and malformed auth.

## Motivation

Jira still used throwing validation helpers for connector protocol failures:
missing handles and malformed auth. Those should be returned anomaly values,
with exception conversion left to an outer boundary.

## Base Branch

`main`

## Layer

Connector implementation refactor.

## What Changed

- Adds a direct `ai.miniforge/anomaly` dependency to `connector-jira`.
- Changes the private handle lookup helper to call `connector/require-handle`.
- Makes `do-discover` and `do-extract` return response-shaped
  missing-handle anomalies.
- Makes missing site config and malformed auth return anomaly values from
  `do-connect`.
- Updates Jira connector tests from thrown `ExceptionInfo` assertions to
  returned anomaly assertions.

The returned connector-boundary maps use `response/make-anomaly` so existing
consumers that dispatch with `response/anomaly-map?` continue to treat failures
as failures.

## Testing

- Focused Jira connector tests pass.
- Full `bb pre-commit` pass required before merge.

## Follow-Up

The shared throwing validation helpers remain until the remaining connector
implementations are migrated in subsequent slices.
