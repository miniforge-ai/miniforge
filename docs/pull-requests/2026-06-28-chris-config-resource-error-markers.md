<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Mark Config Resource Parse Guards Invalid

## Summary

Mark malformed EDN and non-map classpath config resources as explicit
invalid-config failures.

## Motivation

The shared classpath config resource loader already fails fast when required
config resources are malformed or parse to non-map values. Those are boot-time
configuration contract failures, not recoverable runtime outcomes. Adding
explicit invalid-config ex-data makes that intent visible to the standards
scanner while preserving the precise parse reason for diagnostics.

## Changes

- Add `:config/error :invalid-config` for malformed EDN and non-map resources.
- Add `:config/invalid-config-reason` to preserve `:malformed-edn` and
  `:not-a-map`.
- Extend the existing config resource tests for the new ex-data contract.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.config.resource-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.config.resource-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :cleanup-needed (count cleanup)))'
```
