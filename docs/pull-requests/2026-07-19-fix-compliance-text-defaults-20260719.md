<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Normalize compliance scanner text

## Overview

Resolve two Dewey 210 map-default findings where compliance-scanner logic requires string values.

## Motivation

Review-comment payloads require string rationales, and exception-boundary classification applies string operations to
function documentation. Missing, nil, false, or malformed values must consistently behave as empty text.

## Changes in Detail

- Normalize violation rationales to string-only payload text.
- Normalize parsed function documentation before boundary classification.
- Add regression coverage for absent and malformed values at both call sites.

## Testing Plan

- Run focused compliance-scanner tests.
- Run the Dewey 210 scanner and verify two findings are removed from this branch.
- Run `bb pre-commit`.

## Deployment Plan

Merge normally after CI and review. No data migration is required.

## Related Issues/PRs

- Base branch: `main`.
- Depends on: none.
- Continues the standards-remediation series after #1435.

## Checklist

- [x] Preserve valid rationale and boundary-classification behavior.
- [x] Add malformed-value regression coverage.
- [x] Pass focused scans and tests.
- [ ] Pass CI and resolve review comments.
