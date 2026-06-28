<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Connector Config Invalid Guards

## Summary

Mark GitLab and Jira connector schema validation failures as invalid config
guards.

## Motivation

The GitLab and Jira connector `validate!` helpers are config boundary checks.
They already throw structured anomaly data for invalid connector config, but the
exception-as-data scanner could not distinguish them from ordinary recoverable
runtime failures. Adding explicit `:config/error :invalid-config` data preserves
the existing behavior while making the boundary intent visible.

## Changes

- Add `:config/error :invalid-config` to GitLab connector schema validation
  failures.
- Add `:config/error :invalid-config` to Jira connector schema validation
  failures.
- Extend the existing anomaly tests to assert the new ex-data key.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.connector-gitlab.anomaly.gitlab-anomaly-test 'ai.miniforge.connector-jira.anomaly.jira-anomaly-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.connector-gitlab.anomaly.gitlab-anomaly-test 'ai.miniforge.connector-jira.anomaly.jira-anomaly-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :cleanup-needed (count cleanup)))'
```
