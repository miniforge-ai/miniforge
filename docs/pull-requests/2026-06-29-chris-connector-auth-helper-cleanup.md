<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor: remove connector throwing auth helpers

## Overview

Removes the deprecated shared `connector/validate-auth!` and
`connector/validate-auth-or-throw!` APIs.

## Motivation

The shared throwing auth helpers existed only as incremental migration bridges.
Connector auth callsites now use `connector/validate-auth` and return
response-shaped anomalies at the connector boundary, so the throwers preserve
exceptions-as-data violations without serving production callers.

## Base Branch

`main`

## Layer

Shared connector validation API cleanup.

## What Changed

- Removes `ai.miniforge.connector.validation/validate-auth!`.
- Removes `ai.miniforge.connector.validation/validate-auth-or-throw!`.
- Removes the public connector interface vars for both throwers.
- Deletes obsolete throwing-auth compatibility tests.
- Removes the no-longer-used response dependency from connector validation.

## Testing

- Focused connector validation tests pass.
- Full `bb pre-commit` pass required before merge.

## Follow-Up

This completes the shared connector validation thrower removal.
