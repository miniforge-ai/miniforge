<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Add `reachable-count` to graph.clj and expose through interface.clj.

**PR:** [#1226](https://github.com/miniforge-ai/miniforge/pull/1226)
**Branch:** `mf/add-reachable-count-to-graphclj-and-expo-548e3f70`

## Summary

Add `reachable-count` to graph.clj and expose through interface.clj.

## Files Changed

- `components/algorithms/src/ai/miniforge/algorithms/graph.clj` (modify)
- `components/algorithms/src/ai/miniforge/algorithms/interface.clj` (modify)

## Test Results

_No test artifacts available._

## Review Decision

**Decision**: approved

Correct and clean. `reachable-count` delegates to the existing `dfs` with `identity` as the deps extractor, three constant-nil callbacks, and returns `(count visited)` from the destructured pair — exactly the spec. Edge cases are sound: absent start yields `count #{}` = 0 via the missing-node branch; self-loops terminate via the visiting-set cycle guard and still count the start; general cycles terminate for the same reason. The function sits in Layer 1, below `dfs-collect`, calling Layer 0 — DAG order is preserved. The `interface.clj` entry follows the exact `(def name docstring graph/fn)` pattern used by every sibling entry. Tests pass (106/191 graph-test, 20/95 interface-test, both with the new function covered). One nit in the rich-comment block; no blocking or warning issues.

### Known issues (non-blocking)

Merged with 1 unresolved non-blocking issue(s) recorded for follow-up:

- `components/algorithms/src/ai/miniforge/algorithms/graph.clj` — The inline comment '; reachable-count: count nodes reachable from a start in an adjacency map' restates the function name without adding why (dewey 009). The examples that follow are self-labeling; the comment narrates rather than illuminates.
