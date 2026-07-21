<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Normalize CLI typed defaults

## Overview

Resolve the final two Dewey 210 findings in the CLI base with explicit type-aware defaults.

## Motivation

PR policy summaries render commit SHAs as text, while workflow response projection merges output maps. Missing, nil,
false, or malformed values must fall back to the display sentinel and empty map without stringifying or merging invalid
data.

## Changes in Detail

- Normalize policy-response commit SHAs to strings or the em-dash sentinel.
- Normalize workflow response output to a map before projection.
- Add regression coverage for valid, absent, and malformed values.

## Testing Plan

- Run focused CLI tests.
- Run the Dewey 210 scanner and verify two findings are removed from this branch.
- Run `bb pre-commit`.

## Deployment Plan

Merge normally after CI and review. No data migration is required.

## Related Issues/PRs

- Base branch: `main`.
- Depends on: none.
- Continues the standards-remediation series after the agent typed-default wave.

## Checklist

- [x] Preserve valid commit and response output values.
- [x] Add malformed-value regression coverage.
- [x] Pass focused scans and tests.
- [ ] Pass CI and resolve review comments.
