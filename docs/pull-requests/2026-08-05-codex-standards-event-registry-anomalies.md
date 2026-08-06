<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: propagate missing event registry anomalies

## Overview

Makes a missing event-type registry resource an explicit `:not-found` anomaly.

## Changes in Detail

- Return an anomaly at the resource loading boundary.
- Preserve that anomaly in derived registry views and audit output.

## Testing Plan

- Event-stream tests
- Normal pre-commit validation

## Deployment Plan

No migration or rollout is needed.

## Related Issues/PRs

- Base Branch: `main`
- Depends On: none

## Checklist

- [x] Audit gap fixed
- [x] Pre-commit checks passed
