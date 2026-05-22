<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Audit and fix CLI and workflow output rendering to ensure

**PR:** [#961](https://github.com/miniforge-ai/miniforge/pull/961)
**Branch:** `mf/audit-and-fix-cli-and-workflow-output-re-271a0d0c`

## Summary

Audit and fix CLI and workflow output rendering to ensure artifact-related WARN messages are not rendered as ERROR-level
output in terminal.

## Files Changed

- `components/agent/src/ai/miniforge/agent/artifact_session.clj` (modify) — adds the `:info/mcp-artifact-skipped`
  diagnostic and tightens the `:warn/no-artifact-found` gating so it only fires when no worktree role file existed on
  disk.
- `components/agent/test/ai/miniforge/agent/artifact_session_test.clj` (modify) — regression coverage for the new
  diagnostic and gating.
- `docs/pull-requests/2026-05-21-audit-and-fix-cli-and-workflow-output-rendering-to-ensure.md` (create) — this PR doc.

## Test Results

_No test artifacts available._

## Review Decision

_No review artifacts available._
