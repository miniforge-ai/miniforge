<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Mark Policy Calibration Config Guard Invalid

## Summary

Mark policy-calibration config resource failures as explicit invalid-config
setup failures.

## Motivation

Policy calibration loads a required classpath config resource during setup.
Missing resources, unreadable resources, malformed EDN, non-map EDN, and
schema-invalid maps are configuration contract failures. They should carry
explicit invalid-config data rather than appearing as ordinary recoverable
runtime exceptions.

## Changes

- Replace the `IllegalArgumentException` schema failure with `ex-info` carrying
  invalid-config ex-data.
- Distinguish missing, unreadable, malformed, non-map, and schema-invalid
  resource states.
- Add focused regression coverage for each invalid resource path.
- Add standard Clojure file headers to the touched policy-calibration files.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.policy-calibration.interface-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.policy-calibration.interface-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :cleanup-needed (count cleanup)))'
```

```bash
bb pre-commit
```
