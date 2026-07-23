<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Normalize tool search text

## Overview

Resolve the two Dewey 210 map-default findings in tool-registry search with explicit malformed-value semantics.

## Motivation

Tool names and descriptions are searched case-insensitively, but optional or malformed metadata must not cause
`clojure.string/lower-case` to fail. Search should use only string metadata and treat every other value as empty text.

## Changes in Detail

- Normalize searchable tool metadata through a string-only helper.
- Preserve matching for valid names and descriptions while ignoring missing, nil, false, keyword, and numeric values.
- Add regression coverage for malformed metadata in both searchable fields.

## Testing Plan

- Run focused tool component tests.
- Run the Dewey 210 scanner and verify two findings are removed from this branch.
- Run `bb pre-commit`.

## Deployment Plan

Merge normally after CI and review. No data migration is required.

## Related Issues/PRs

- Base branch: `main`.
- Depends on: none.
- Continues the standards-remediation series after #1433.

## Checklist

- [x] Preserve valid tool search behavior.
- [x] Add malformed-metadata regression coverage.
- [x] Pass focused scans and tests.
- [ ] Pass CI and resolve review comments.
