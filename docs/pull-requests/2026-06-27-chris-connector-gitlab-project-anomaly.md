# Fix connector-gitlab project anomaly

## Summary

The exceptions-as-data scan reports cleanup-needed throws in the GitLab
connector implementation. Missing project config and non-permanent optional
resource failures are normal connector failure data, not boundary exceptions.

This change:

- returns canonical `:anomalies/incorrect` data from `do-connect` when
  `:gitlab/project-id` and `:gitlab/project-path` are both absent
- returns the HTTP failure anomaly for non-permanent optional-resource extract
  failures instead of rethrowing
- preserves the existing permanent optional-resource behavior of returning an
  empty extract result

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.connector-gitlab.impl-test 'ai.miniforge.connector-gitlab.anomaly.gitlab-anomaly-test 'ai.miniforge.pipeline-runner.interface-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.connector-gitlab.impl-test 'ai.miniforge.connector-gitlab.anomaly.gitlab-anomaly-test 'ai.miniforge.pipeline-runner.interface-test)"
```

- Exceptions-as-data scanner: 149 cleanup-needed rows; no
  `connector_gitlab/impl`, `pipeline_runner`, or `connector/protocol` rows.
