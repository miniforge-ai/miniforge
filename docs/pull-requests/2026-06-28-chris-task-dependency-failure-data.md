<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Task Dependency Failure Data

## Summary

Record dependency-cascade task failures as structured data instead of a
synthetic `ex-info` value.

## Motivation

When a task failed, the task-executor orchestrator marked dependents failed
with an `ex-info` object. `dag/mark-failed!` stores opaque error data directly,
so the exception wrapper was unnecessary and kept an exceptions-as-data scanner
row alive.

## Changes

- Store dependency-cascade failures as `{:message ..., :dependency-id ...}`.
- Pass the logger argument to `dag/mark-failed!` in both orchestrator failure
  paths, fixing the stale three-argument calls.
- Add focused coverage for the dependent-task error shape.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.task-executor.orchestrator-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.task-executor.orchestrator-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :cleanup-needed (count cleanup)) (doseq [v (->> cleanup (filter #(clojure.string/includes? (:file %) "task_executor/orchestrator.clj")) (sort-by :line))] (println (:file v) (:line v))))'
```
