# refactor: return GitLab connector validation anomalies

## Overview

Migrates the GitLab connector away from shared throwing validation helpers for
missing handles and malformed auth.

## Motivation

The GitLab connector had already returned an anomaly for missing project config,
but handle lookup and auth validation still flowed through `ex-info`. Those are
ordinary connector protocol failures and should remain values until an outer
process boundary decides how to present them.

## Base Branch

`main`

## Layer

Connector implementation refactor.

## What Changed

- Adds a direct `ai.miniforge/anomaly` dependency to `connector-gitlab`.
- Changes the private handle lookup helper to call `connector/require-handle`.
- Makes `do-discover` and `do-extract` return response-shaped
  missing-handle anomalies.
- Makes malformed auth return a localized validation anomaly from
  `do-connect`.
- Updates GitLab connector tests from thrown `ExceptionInfo` assertions to
  returned anomaly assertions.

The returned maps use `response/make-anomaly` so existing connector consumers
that dispatch with `response/anomaly-map?` continue to treat failures as
failures.

## Testing

- Focused GitLab connector tests pass.
- Full `bb pre-commit` pass required before merge.

## Follow-Up

The shared throwing validation helpers remain until the remaining connector
implementations are migrated in subsequent slices.
