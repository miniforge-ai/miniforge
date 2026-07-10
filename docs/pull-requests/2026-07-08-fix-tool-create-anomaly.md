<!--
  Title: Fix Tool Create Anomaly
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Return anomaly for invalid tool IDs

## Overview

This PR changes `tool/create-tool` invalid-id handling from exception control
flow to an anomaly return value.

## Motivation

`components/tool` is not a boundary namespace. Invalid user-provided tool
configuration is normal data validation failure, so it should return an anomaly
instead of throwing an `ex-info`.

## Changes in Detail

- Return `:invalid-input` anomaly data when `:id` is not a namespaced keyword.
- Update public `create-tool` documentation to describe the anomaly return.
- Update tests to assert anomaly shape instead of exception behavior.

## Testing Plan

- `bb pre-commit`

## Deployment Plan

No deployment steps. Merge after CI and review pass.

## Related Issues/PRs

- Follow-up from the current `bb review` exceptions-as-data finding.

## Checklist

- [ ] Validate the invalid tool-id path returns an anomaly.
- [ ] Run pre-commit validation.
- [ ] Open PR and resolve review comments.
