<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Return Tool Registry Anomalies

## Summary

Return anomaly maps for ordinary tool-registry validation and lookup outcomes.

## Motivation

The in-memory tool registry still escalated schema-invalid tool configs,
missing update targets, and schema-invalid updates via `response/throw-anomaly!`
inside a non-boundary namespace. These are registry data outcomes and should be
composable anomaly maps, not thrown control flow.

## Changes

- Return `:invalid-input` / `:anomalies/incorrect` anomaly data for
  schema-invalid `register-tool` input.
- Return `:not-found` / `:anomalies/not-found` anomaly data for missing
  `update-tool` targets.
- Return `:invalid-input` / `:anomalies/incorrect` anomaly data for invalid
  merged updates.
- Reject `:tool/id` changes in `update-tool` so registry keys and stored tool
  maps cannot diverge.
- Update public tool-registry docs and regression tests.
- Leave the non-namespaced tool id shape guard as a fatal-only registry
  invariant.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.tool-registry.registry-test 'ai.miniforge.tool-registry.anomaly.registry-anomaly-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.tool-registry.registry-test 'ai.miniforge.tool-registry.anomaly.registry-anomaly-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :tool-registry (count (filter #(= "components/tool-registry/src/ai/miniforge/tool_registry/registry.clj" (:file %)) cleanup))))'
```

```bash
bb pre-commit
```
