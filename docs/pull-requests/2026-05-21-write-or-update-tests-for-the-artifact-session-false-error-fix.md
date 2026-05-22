<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Write or update tests for the artifact-session false-error fix

**PR:** [#955](https://github.com/miniforge-ai/miniforge/pull/955)
**Branch:** `mf/write-or-update-tests-for-the-artifact-s-88a93d28`

## Summary

Write or update tests for the artifact-session false-error fix.

## Files Changed

- `components/agent/src/ai/miniforge/agent/artifact_session.clj` (modify) — `read-artifact` returns nil silently when
  the MCP file is absent; `run-session` emits the new `:warn/no-artifact-found` diagnostic only when both MCP and
  worktree channels are empty.
- `components/agent/test/ai/miniforge/agent/artifact_session_error_test.clj` (modify) — adds regression coverage for the
  cases above.
- `docs/pull-requests/2026-05-21-write-or-update-tests-for-the-artifact-session-false-error-fix.md` (create) — this PR
  doc.

## Test Results

_No test artifacts available._

## Review Decision

_No review artifacts available._
