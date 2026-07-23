<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Validate worktree Git output

## Overview

Resolve the two Dewey 210 map-default findings in the worktree executor with explicit command-output semantics.

## Motivation

Git command stdout is expected to be a string, but missing, nil, or malformed values must not reach string functions.
Branch resolution can safely decline an unusable SHA, while parent-repository derivation must return an error rather
than fabricate a path from empty output.

## Changes in Detail

- Normalize Git stdout through a string-only helper.
- Treat absent, blank, and malformed branch-resolution output as unresolved.
- Return a canonical error when successful `--git-common-dir` execution yields unusable output.
- Add regression coverage for both call sites.

## Testing Plan

- Run focused dag-executor tests.
- Run the Dewey 210 scanner and verify two findings are removed from this branch.
- Run `bb pre-commit`.

## Deployment Plan

Merge normally after CI and review. No data migration is required.

## Related Issues/PRs

- Base branch: `main`.
- Depends on: none.
- Continues the standards-remediation series after #1434.

## Checklist

- [x] Preserve valid Git output handling.
- [x] Add unusable-output regression coverage.
- [x] Pass focused scans and tests.
- [ ] Pass CI and resolve review comments.
