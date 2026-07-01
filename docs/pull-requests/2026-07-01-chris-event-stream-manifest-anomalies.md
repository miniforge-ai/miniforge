<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Event Stream Manifest Anomalies

## Summary

Convert expected event-stream manifest state-machine and validation failures
from thrown exceptions to canonical anomaly data.

## Changes

- Return `:conflict` anomaly maps for illegal archive status transitions.
- Return `:invalid-input` anomaly maps for invalid manifest shapes.
- Propagate manifest anomalies through archive orchestration before filesystem
  side effects continue.
- Update manifest/archive regressions to assert the data-returning contract.

## Validation

- `clojure -M:dev:test -e '(require (quote clojure.test) (quote ai.miniforge.event-stream.manifest-test) (quote
  ai.miniforge.event-stream.archive-test)) (let [r (clojure.test/run-tests (quote
  ai.miniforge.event-stream.manifest-test) (quote ai.miniforge.event-stream.archive-test))] (when (pos? (+ (:fail r)
  (:error r))) (System/exit 1)))'`
- Scoped exceptions-as-data scanner: `{:cleanup-needed 0, :fatal-only 1}`
- `clj-kondo --lint` on changed event-stream source/test files
- `bb pre-commit`
