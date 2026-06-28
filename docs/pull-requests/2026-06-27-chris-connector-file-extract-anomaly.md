# Fix connector-file extract anomaly

## Summary

The exceptions-as-data scan reports one cleanup-needed throw in
`connector-file` when extracting from a missing source file. Missing input data
is normal connector failure data, not a boundary exception.

This change:

- returns a canonical `:anomalies/not-found` map from `do-extract` when the
  source file is unavailable
- documents that connector `extract` may return anomaly maps
- teaches pipeline-runner ingest stages to preserve extract anomaly context as
  the failed stage result

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.connector-file.interface-test 'ai.miniforge.connector-file.anomaly.file-anomaly-test 'ai.miniforge.pipeline-runner.interface-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.connector-file.interface-test 'ai.miniforge.connector-file.anomaly.file-anomaly-test 'ai.miniforge.pipeline-runner.interface-test)"
```

- Exceptions-as-data scanner: 150 cleanup-needed rows; no
  `connector_file/impl`, `pipeline_runner`, or `connector/protocol` rows.
