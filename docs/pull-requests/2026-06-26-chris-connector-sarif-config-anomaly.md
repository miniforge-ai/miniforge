<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Connector SARIF Config Anomaly

## Summary

Return anomaly data for invalid SARIF connector config instead of throwing from
the connector implementation.

## Changes

- Convert `connector-sarif.impl/do-connect` invalid config handling from
  `response/throw-anomaly!` to `response/make-anomaly`.
- Use namespaced SARIF anomaly context via `:sarif/errors`.
- Preserve returned connect anomalies as immediate pipeline stage failures.
- Update implementation and anomaly tests to assert returned anomaly maps.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.connector-sarif.impl-test 'ai.miniforge.connector-sarif.anomaly.sarif-anomaly-test 'ai.miniforge.pipeline-runner.interface-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.connector-sarif.impl-test 'ai.miniforge.connector-sarif.anomaly.sarif-anomaly-test 'ai.miniforge.pipeline-runner.interface-test)"
```

- Exceptions-as-data scanner: no `connector_sarif/impl` cleanup-needed rows.
