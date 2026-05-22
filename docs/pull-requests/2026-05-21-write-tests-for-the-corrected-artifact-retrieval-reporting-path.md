<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Tighten artifact retrieval reporting + add test coverage

**PR:** [#962](https://github.com/miniforge-ai/miniforge/pull/962)
**Branch:** `mf/write-tests-for-the-corrected-artifact-r-fc4d5913`

## Summary

The dogfood task description for this PR was framed as "write tests
for the corrected artifact retrieval/reporting path," but resolving
that task required production-logic changes too. This PR therefore
ships both:

1. **Runtime changes** in `artifact_session.clj`:
   - New `:info/mcp-artifact-skipped` diagnostic emitted when the
     worktree-promotion channel succeeded so callers don't conflate
     "MCP channel unused" with "agent submitted nothing."
   - Tighter gating around `:warn/no-artifact-found` so it fires
     only when no worktree role file was present on disk (in host
     mode); parse-failed files in host mode are suppressed because
     `:warn/worktree-artifact-parse` already covers that.
2. **Test coverage** for the new diagnostic and the tightened gate
   across both `artifact_session_error_test.clj` and
   `artifact_session_test.clj`.

## Files Changed

- `components/agent/src/ai/miniforge/agent/artifact_session.clj` (modify) — adds `:info/mcp-artifact-skipped`, tightens
  `:warn/no-artifact-found` gating.
- `components/agent/test/ai/miniforge/agent/artifact_session_error_test.clj` (modify) — regression coverage for the
  corrected reporting path.
- `components/agent/test/ai/miniforge/agent/artifact_session_test.clj` (modify) — additional host/capsule matrix
  coverage for the diagnostic and gate.
- `docs/pull-requests/2026-05-21-write-tests-for-the-corrected-artifact-retrieval-reporting-path.md` (create) — this PR
  doc.

## Test Results

_No test artifacts available._

## Review Decision

_No review artifacts available._
