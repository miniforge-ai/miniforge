<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Add monitor-worklist namespace to pr-lifecycle component.

**PR:** [#1198](https://github.com/miniforge-ai/miniforge/pull/1198)
**Branch:** `mf/add-monitor-worklist-namespace-to-pr-lif-db6b9274`

## Summary

Add monitor-worklist namespace to pr-lifecycle component.

## Files Changed

- `components/pr-lifecycle/resources/config/pr-lifecycle/messages/system.edn` (create)
- `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_worklist.clj` (modify)
- `components/pr-lifecycle/test/ai/miniforge/pr_lifecycle/monitor_worklist_test.clj` (create)
- `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/interface.clj` (modify)
- `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_worklist.clj` (modify)

## Test Results

_No test artifacts available._

## Review Decision

**Decision**: approved

Scoped files are correct. Named constants, localization, try+, anomaly returns, schema validation placement, and interface passthrough shape all conform to the active standards. Two nits on implementation style (apply str, two-arm cond) and a warning on the JSON regex fragility in fetch-pr-state — the gh --json output is typically compact, but the regex needs a \s* guard to survive optional whitespace. No blocking issues in scope.

### Known issues (non-blocking)

Merged with 4 unresolved non-blocking issue(s) recorded for follow-up:

- `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_worklist.clj:56` — gh-state-pattern regex does not allow whitespace between the JSON colon and the value. Pattern #"\"state\":\"([^\"]+)\"" fails on {"state": "OPEN"} (space after colon). GitHub CLI --json typically emits compact JSON, but that is not guaranteed and the fragility violates the intent of the named-constant docstring ('regex to extract the state field').
- `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_worklist.clj:148` — prune-closed-prs returns the pruned WorklistEntry without updating :worklist/updated-at. Callers that persist the result get a map whose timestamp still reflects the pre-prune write, not the time of pruning. The spec says the field tracks 'last-write instant', so a prune that removes entries is semantically a write.
- `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_worklist.clj:70` — (apply str (map #(format ...) bytes)) is idiomatic but accumulates a lazy seq through apply. clojure.string/join is the conventional form for collecting strings.
- `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/monitor_worklist.clj:102` — load-worklist uses (cond (not ...) ... :else ...) with exactly two arms. A plain if reads more directly.
