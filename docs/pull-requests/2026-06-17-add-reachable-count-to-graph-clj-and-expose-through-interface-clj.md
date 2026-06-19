<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Add `reachable-count` to graph.clj and expose through interface.clj

**PR:** [#1226](https://github.com/miniforge-ai/miniforge/pull/1226)
**Branch:** `mf/add-reachable-count-to-graphclj-and-expo-548e3f70`

## Summary

Add `reachable-count` to graph.clj and expose through interface.clj.

## Files Changed

- `components/algorithms/src/ai/miniforge/algorithms/graph.clj` (modify)
- `components/algorithms/src/ai/miniforge/algorithms/interface.clj` (modify)
- `components/algorithms/test/ai/miniforge/algorithms/graph_test.clj` (modify)
- `bb.edn` (modify)
- `work/dogfood-monitor-retest.spec.edn` (add)

## Test Results

- Focused graph tests: 116 tests, 204 assertions, 0 failures, 0 errors.

## Review Decision

**Decision**: approved

Correct and clean. `reachable-count` delegates to the existing `dfs` with `identity` as the deps extractor, three
constant-nil callbacks, and returns `(count visited)` from the destructured pair — exactly the spec. Edge cases are
sound: absent start yields `count #{}` = 0 via the missing-node branch; self-loops terminate via the visiting-set cycle
guard and still count the start; general cycles terminate for the same reason. The function sits in Layer 1, below
`dfs-collect`, calling Layer 0 — DAG order is preserved. The `interface.clj` entry follows the exact `(def name
docstring graph/fn)` pattern used by every sibling entry. Tests pass with focused reachable-count coverage.

### Review Follow-up

Resolved the rich-comment header nit and added regression coverage for the documented reachable-count edge cases.
