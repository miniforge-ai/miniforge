<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Add tests for reachable-count in graph_test.clj.

**PR:** [#1227](https://github.com/miniforge-ai/miniforge/pull/1227)
**Branch:** `mf/add-tests-for-reachable-count-in-graphte-bae37cb7`

## Summary

Add tests for reachable-count in graph_test.clj.

## Files Changed

- `components/algorithms/test/ai/miniforge/algorithms/graph_test.clj` (modify)

## Test Results

_No test artifacts available._

## Review Decision

**Decision**: approved

All 10 spec-required edge cases are present and assert the correct values. The `->adj` conversion helper is correctly factored, well-named, and private. Tests run green (204/204 assertions). One nit: a redundant inline comment inside the missing-deps test paraphrases the testing string.

### Known issues (non-blocking)

Merged with 1 unresolved non-blocking issue(s) recorded for follow-up:

- `components/algorithms/test/ai/miniforge/algorithms/graph_test.clj` — Inline comment inside `reachable-count-missing-deps` restates what the `testing` string already says (rule 009 — comments explain WHY, not WHAT). 'neighbours absent from adj-map are not counted' plus the fixture name is sufficient; the comment spelling out the fixture contents adds no new information.
