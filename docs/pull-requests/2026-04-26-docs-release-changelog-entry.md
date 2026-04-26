<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs/release-2026-04-26

## Summary

Adds a structured release entry to [CHANGELOG.md](CHANGELOG.md) for
`2026.04.26.1`, including:

- release highlights
- stable/version tag names
- a linked merged-PR inventory for the release range
- an explicit note that structured linked release inventory starts with this
  release because earlier tagged releases were not consistently maintained in
  the changelog file

## Validation

- `markdownlint CHANGELOG.md docs/pull-requests/2026-04-26-docs-release-changelog-entry.md`
- commit hook / `bb pre-commit`
