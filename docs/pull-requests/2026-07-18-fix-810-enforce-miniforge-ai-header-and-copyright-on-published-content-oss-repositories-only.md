<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Add missing published-content copyright headers

## Overview

Add the required Apache 2.0 copyright header to 124 published Markdown files identified by the compliance scanner.

## Motivation

The repository's Dewey 810 rule requires the verbatim Miniforge.ai header on published content. The current scan reported
124 files without it, mostly historical pull-request documentation.

## Changes in Detail

- Add the required multiline header to 124 Markdown files.
- Preserve the existing document content below each inserted header.
- Repair Markdown formatting findings exposed when the touched files entered the pre-commit lint scope.

## Testing Plan

- Run the Dewey 810 scanner and confirm zero header findings.
- Run `bb pre-commit` through the normal commit hook.
- Run `git diff --check`.

## Deployment Plan

Merge normally. This is published-content metadata and formatting only; it has no runtime deployment impact.

## Related Issues/PRs

- Replaces closed PR #1419 after its generated header template used literal `\\n` sequences.
- Base branch: `main`.
- Depends on: none.

## Checklist

- [x] Apply all 124 required headers.
- [x] Preserve document bodies.
- [x] Pass the focused Dewey 810 scan.
- [x] Pass pre-commit and reduce the full scan from 149 findings to the expected 25 deferred findings.
