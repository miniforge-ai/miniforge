<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Mark Workflow Resume Config Guard Invalid

## Summary

Mark missing workflow-resume config resources as explicit invalid-config setup
failures.

## Motivation

Workflow resume behavior is driven by a compiled classpath EDN resource. A
missing `config/workflow-resume/resume.edn` resource is a packaging/configuration
fault, not an ordinary runtime workflow-not-found condition, so the anomaly data
should carry the invalid-config marker used by the other config guard cleanups.

## Changes

- Add `:config/error :invalid-config` to the missing resume config resource
  anomaly.
- Add regression coverage using `try+` against the thrown anomaly map.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.workflow-resume.core-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.workflow-resume.core-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :workflow-resume (count (filter #(= "components/workflow-resume/src/ai/miniforge/workflow_resume/core.clj" (:file %)) cleanup))))'
```

```bash
bb pre-commit
```
