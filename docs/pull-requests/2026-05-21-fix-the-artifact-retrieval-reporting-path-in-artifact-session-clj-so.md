<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix the artifact retrieval/reporting path in artifact_session.clj so...

**PR:** [#960](https://github.com/miniforge-ai/miniforge/pull/960)
**Branch:** `mf/fix-the-artifact-retrievalreporting-path-4464ed9c`

## Summary

Fix the artifact retrieval/reporting path in artifact_session.clj so that the no-artifact-found warning does not fire when a valid worktree-promoted artifact exists.

## Files Changed

- `components/agent/src/ai/miniforge/agent/artifact_session.clj` (modify)
- `components/agent/test/ai/miniforge/agent/artifact_session_test.clj` (modify)

## Test Results

_No test artifacts available._

## Review Decision

_No review artifacts available._
