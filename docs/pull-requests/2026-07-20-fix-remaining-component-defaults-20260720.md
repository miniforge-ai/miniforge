<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Normalize remaining component defaults

## Overview

Resolve the final three component-layer Dewey 210 findings with explicit type-aware defaults.

## Motivation

Anomaly messages require strings, dashboard badge variants require keywords, and environment metadata requires a map.
Missing, nil, false, or malformed values must consistently use the documented fallback for each contract.

## Changes in Detail

- Normalize DAG error messages to strings or the canonical unwrap fallback.
- Normalize workflow dependency badge severity to a keyword or `:warning`.
- Normalize acquired environment metadata to a map before adding the base SHA.
- Add regression coverage for valid, absent, and malformed values.

## Testing Plan

- Run focused tests for dag-primitives, web-dashboard, and workflow.
- Run the Dewey 210 scanner and verify three findings are removed from this branch.
- Run `bb pre-commit`.

## Deployment Plan

Merge normally after CI and review. No data migration is required.

## Related Issues/PRs

- Base branch: `main`.
- Depends on: none.
- Final component-layer wave in the current standards-remediation series.

## Checklist

- [x] Preserve valid message, severity, and metadata values.
- [x] Add malformed-value regression coverage.
- [x] Pass focused scans and tests.
- [ ] Pass CI and resolve review comments.
