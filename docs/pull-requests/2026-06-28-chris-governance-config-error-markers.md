<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Mark Governance Config Guards Invalid

## Summary

Mark governance config guard failures as explicit invalid-config failures.

## Motivation

Governance pack overrides and governance config lookup are configuration
contract checks. Untrusted pack overrides, shrinking knowledge-safety patterns,
and unknown governance config keys should fail fast as invalid configuration
rather than look like ordinary recoverable runtime exceptions in the standards
scanner.

## Changes

- Add `:config/error :invalid-config` to untrusted pack override failures.
- Add `:config/error :invalid-config` to knowledge-safety pattern shrink
  failures.
- Add `:config/error :invalid-config` to unknown governance config keys.
- Preserve precise `:config/invalid-config-reason` values and extend tests.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.config.governance-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.config.governance-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :cleanup-needed (count cleanup)))'
```
