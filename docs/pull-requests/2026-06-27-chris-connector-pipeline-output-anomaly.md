<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Connector Pipeline Output Publish Anomalies

## Summary

- Return `:anomalies/not-found` data when `connector-pipeline-output` publish
  receives an unknown output handle.
- Fail pipeline publish stages when sink connectors return anomaly data from
  `publish`, matching existing connect and extract handling.
- Add focused regression coverage for direct output publish and pipeline-runner
  publish anomaly propagation.

## Validation

- `clojure -M:dev:test` focused connector-pipeline-output and pipeline-runner
  tests.
