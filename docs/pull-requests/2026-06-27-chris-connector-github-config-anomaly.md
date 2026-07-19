<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Fix connector-github config anomaly

## Summary

The exceptions-as-data scan reports a cleanup-needed throw in
`connector-github` config validation. Invalid connector configuration is normal
caller input failure data, not a boundary exception.

This change:

- returns canonical `:anomalies/incorrect` maps from `do-connect` for malformed
  GitHub config and missing `:github/owner` / `:github/org`
- preserves existing auth and resource lookup boundary behavior for separate
  case-by-case remediation
- relies on pipeline-runner's existing connect anomaly handling to surface the
  failure as an immediate failed stage

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.connector-github.impl-test 'ai.miniforge.connector-github.anomaly.github-anomaly-test 'ai.miniforge.pipeline-runner.interface-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.connector-github.impl-test 'ai.miniforge.connector-github.anomaly.github-anomaly-test 'ai.miniforge.pipeline-runner.interface-test)"
```

- Exceptions-as-data scanner: 149 cleanup-needed rows; no
  `connector_github/impl`, `pipeline_runner`, or `connector/protocol` rows.
