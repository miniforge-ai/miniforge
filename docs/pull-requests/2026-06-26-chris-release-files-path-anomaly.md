<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Fix: Return release file path traversal as data

## Overview

This PR converts the `release-executor.files` worktree path traversal guard from
a thrown exception into anomaly data folded into the existing file-operation
result map.

## Motivation

The exceptions-as-data cleanup scan reports one throw site in
`release-executor.files`. Escaped artifact paths are invalid input to the file
operation boundary and can be represented as data while preserving the existing
`{:success? false :error ...}` result contract for callers.

## Changes in Detail

- Add the anomaly component dependency to `release-executor`.
- Return `:invalid-input` anomaly maps from escaped worktree path validation.
- Preserve existing `process-file-action` failure result fields.
- Add focused coverage for valid creates, escaped create/modify/delete actions,
  and symlink-based worktree escapes.

## Testing Plan

- Run focused `release-executor.files` tests.
  Latest result: 5 tests, 18 assertions, 0 failures.
- Run the exceptions-as-data scanner and confirm `release-executor.files`
  contributes zero cleanup-needed rows.
  Latest scanner count: 154 cleanup-needed rows; `release-executor.files`
  contributes zero rows.
- Run `bb pre-commit`.
  Latest result: all checks passed.

## Deployment Plan

No deployment steps. This is a release file-operation validation cleanup.

## Related Issues/PRs

- Follows PR #1283.

## Checklist

- [x] Implementation updated.
- [x] Tests updated and focused suite passes.
- [x] `bb review` count reduced.
- [x] `bb pre-commit` passes.
- [ ] PR opened, comments resolved, CI green, and merged.
