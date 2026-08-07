<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: return anomalies from control boundaries

## Overview

Replaces exception control flow at operator and dashboard control boundaries with explicit anomalies and failure responses.

## Changes in Detail

- Validate operator registrations on the anomaly path.
- Convert failed command submission into a response failure.
- Preserve failure responses in event-stream control execution.

## Testing Plan

- Operator and dashboard focused tests
- Normal pre-commit validation

## Deployment Plan

No migration or rollout is needed.

## Related Issues/PRs

- Base Branch: `main`
- Depends On: none

## Checklist

- [x] Audit gap fixed
- [x] Pre-commit checks passed
