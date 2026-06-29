<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Return Policy Pack Registry Anomalies

## Summary

Return anomaly maps for policy-pack registry validation and not-found outcomes.

## Motivation

The in-memory policy-pack registry still escalated ordinary validation and
lookup failures via `response/throw-anomaly!` inside a non-boundary namespace.
Those outcomes are data-level registry results, not process-fatal programmer
guards, and should be represented as anomaly maps so callers can compose them.

## Changes

- Return `:invalid-input` / `:anomalies/incorrect` anomaly data when
  `register-pack` receives a schema-invalid pack.
- Return `:not-found` / `:anomalies/not-found` anomaly data when `export-pack`
  targets a missing pack.
- Update public registry docs and regression coverage for the returned anomaly
  shape.
- Leave unsupported and not-yet-implemented registry surfaces as fatal-only
  programmer guards.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.policy-pack.anomaly.registry-anomaly-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.policy-pack.anomaly.registry-anomaly-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner]) (quote [clojure.string :as str])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :policy-pack-registry (count (filter #(= "components/policy-pack/src/ai/miniforge/policy_pack/registry.clj" (:file %)) cleanup))))'
```

```bash
bb pre-commit
```
