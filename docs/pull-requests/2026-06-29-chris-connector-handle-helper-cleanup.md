<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: remove connector throwing handle helper

## Overview

Removes the deprecated shared `connector/require-handle!` API now that
connector handle callsites have migrated to anomaly-returning helpers.

## Motivation

The shared throwing helper existed only as an incremental migration bridge. With
the connector implementations moved to returned anomalies, keeping the throwing
API preserves an exceptions-as-data violation without serving production
callers.

## Base Branch

`main`

## Layer

Shared connector validation API cleanup.

## What Changed

- Removes `ai.miniforge.connector.validation/require-handle!`.
- Removes the public `connector/require-handle!` interface var.
- Deletes the obsolete throwing-handle compatibility test.
- Updates connector validation docs to describe the remaining auth-only
  throwing compatibility.

## Testing

- Focused connector validation tests pass.
- Full `bb pre-commit` pass required before merge.

## Follow-Up

Auth throwing compatibility remains until the auth helper cleanup slice.
