<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Normalize agent typed defaults

## Overview

Resolve three Dewey 210 map-default findings in the agent component with explicit type-aware semantics.

## Motivation

Agent telemetry and split-review aggregation consume typed values: artifact sources and roles are keywords, while an
LLM response is a map. Missing, nil, false, or malformed values must fall back to the documented type-safe defaults.

## Changes in Detail

- Normalize implementer artifact-source telemetry to a keyword or `:none`.
- Normalize failed split-review responses to a map before adding aggregate token and cost data.
- Normalize supervisory direct-agent roles to a keyword or `:agent`.
- Add regression coverage for malformed values at all three call sites.

## Testing Plan

- Run focused agent component tests.
- Run the Dewey 210 scanner and verify three findings are removed from this branch.
- Run `bb pre-commit`.

## Deployment Plan

Merge normally after CI and review. No data migration is required.

## Related Issues/PRs

- Base branch: `main`.
- Depends on: none.
- Continues the standards-remediation series after #1436.

## Checklist

- [x] Preserve valid artifact, response, and role values.
- [x] Add malformed-value regression coverage.
- [x] Pass focused scans and tests.
- [ ] Pass CI and resolve review comments.
