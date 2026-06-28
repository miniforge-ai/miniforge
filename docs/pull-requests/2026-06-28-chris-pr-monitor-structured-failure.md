<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR Monitor Structured Failure

## Summary

Replace the PR monitor phase's synthetic `ex-info` failure value with the
existing structured `response/failure` message/data shape.

## Motivation

The PR monitor path already returned failure as data, but constructed that data
through `ex-info`, keeping the exception cleanup scanner row alive and making a
non-throwing path look like exception flow.

## Changes

- Return `response/failure` with `"PR monitoring failed"` plus
  `{:failed-prs ...}` under response error data.
- Add focused coverage for failed PR monitoring to lock status, message, failed
  PR payload, and train-state preservation.

## Validation

- `clojure -M:dev:test -e "(require 'ai.miniforge.phase-software-factory.pr-monitor-test 'clojure.test)
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.pr-monitor-test)"`
- `clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r
  (scanner/scan-exceptions-as-data \".\") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))]
  (println :cleanup-needed (count cleanup)) (doseq [v (->> cleanup (filter #(clojure.string/includes? (:file %)
  \"pr_monitor\")) (sort-by :line))] (println (:file v) (:line v))))'`
