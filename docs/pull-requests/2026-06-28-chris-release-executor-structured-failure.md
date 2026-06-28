<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Release Executor Structured Failure

## Summary

Replace the release phase's synthetic `ex-info` wrapper for
`:success? false` release-executor results with a structured
`response/failure` message/data value.

## Motivation

The release executor failure branch already returned a failure value, but built
that value through `ex-info`. That kept the exception cleanup scanner row alive
and made an exceptions-as-data path look like thrown control flow.

## Changes

- Return `(response/failure (messages/t :release/phase-failed) {:data ...})`
  for executor failure results.
- Preserve executor `:errors` and `:metrics` under response error data.
- Extend release diagnostics coverage to assert the returned failure payload.
- Remove the unused private `verdicts` documentation var from the touched
  release namespace.

## Validation

- `clojure -M:dev:test -e "(require 'ai.miniforge.phase-software-factory.release-test 'clojure.test)
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.release-test)"`
- `clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r
  (scanner/scan-exceptions-as-data \".\") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))]
  (println :cleanup-needed (count cleanup)) (doseq [v (->> cleanup (filter #(clojure.string/includes? (:file %)
  \"phase_software_factory/release.clj\")) (sort-by :line))] (println (:file v) (:line v))))'`
