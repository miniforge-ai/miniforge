<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: return anomalies from durable effect boundaries

## Overview

Moves effect-transaction and execution-grant persistence validation failures onto the anomaly value path.

## Changes in Detail

- Reject unsupported timestamps before writing.
- Propagate persistence anomalies to callers.
- Update focused tests for the returned anomaly contract.

## Testing Plan

- Focused effect-transaction and execution-grant tests
- Normal pre-commit validation

## Deployment Plan

No migration or rollout is needed.

## Related Issues/PRs

- Base Branch: `main`
- Depends On: none

## Checklist

- [x] Audit gap fixed
- [x] Pre-commit checks passed
