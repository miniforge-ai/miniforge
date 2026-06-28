<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Mark Excel Config Guards Invalid

## Summary

Mark Excel connector configuration guard failures as explicit invalid-config
failures.

## Motivation

Excel connector setup failures such as missing required config fields or a
configured sheet absent from the workbook are connector configuration contract
failures. They should carry explicit invalid-config data while preserving the
existing anomaly categories used by callers.

## Changes

- Add invalid-config ex-data for missing Excel URL, sheet name, and columns.
- Add invalid-config ex-data when the configured sheet is absent from the
  workbook.
- Extend Excel anomaly tests for the new data contract.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.connector-excel.anomaly.excel-anomaly-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.connector-excel.anomaly.excel-anomaly-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :cleanup-needed (count cleanup)))'
```
