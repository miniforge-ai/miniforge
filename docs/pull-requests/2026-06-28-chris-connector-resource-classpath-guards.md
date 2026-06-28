<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Connector Resource Classpath Guards

## Summary

Make connector resource registry failures explicit classpath/config-resource guards.

## Motivation

GitHub, GitLab, and Jira connector resource registries are loaded from bundled EDN
resources. A missing registry is a packaging/classpath integrity error, not a
recoverable connector runtime condition. The exception-as-data scanner already
classifies explicit classpath guards as fatal-only, so these sites should declare
that boundary directly.

## Changes

- Prefix missing resource messages with `Missing classpath resource`.
- Include `:config/resource` alongside the existing `:path` value in anomaly data.
- Preserve the existing `response/throw-anomaly!` behavior for missing bundled
  registry resources.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.connector-github.anomaly.github-anomaly-test 'ai.miniforge.connector-gitlab.anomaly.gitlab-anomaly-test 'ai.miniforge.connector-jira.anomaly.jira-anomaly-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.connector-github.anomaly.github-anomaly-test 'ai.miniforge.connector-gitlab.anomaly.gitlab-anomaly-test 'ai.miniforge.connector-jira.anomaly.jira-anomaly-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r)) fatal (filter #(= :fatal-only (:classification %)) (:violations r))] (println :cleanup-needed (count cleanup)) (println :fatal-only (count fatal)))'
```
