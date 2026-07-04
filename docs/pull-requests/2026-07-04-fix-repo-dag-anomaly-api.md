# docs: Align repo-DAG anomaly API documentation

## Overview

This PR removes stale repo-DAG documentation that still described throwing
constructor paths after the public API moved to anomaly-returning behavior.

## Motivation

The repo-DAG API now returns anomaly maps for validation and graph operation
failures. Comments and examples that still imply exception flow make future
callers more likely to add unnecessary `try`/`catch` handling or miss anomaly
checks.

## Changes in Detail

- Update internal repo node/edge builder docs so they no longer refer to
  throwing constructor variants.
- Update the example cycle path to show the anomaly-returning `add-edge` call
  directly instead of wrapping it in exception handling.

## Testing Plan

- `bb pre-commit`

## Deployment Plan

Documentation-only change. Merge normally after review and CI.

## Related Issues/PRs

- Follow-up cleanup after the repo-DAG anomaly-returning API PR series.

## Checklist

- [ ] Confirm diff is documentation-only.
- [ ] Run pre-commit validation.
- [ ] Open PR and resolve review comments.
