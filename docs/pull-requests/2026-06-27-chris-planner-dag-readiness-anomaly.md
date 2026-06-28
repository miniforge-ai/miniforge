<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Planner DAG Readiness Data Failures

## Summary

- Convert planner DAG readiness validation failures from exception-backed
  responses to data-backed `response/error` values.
- Preserve the existing `:anomalies.dag/no-tasks` and
  `:anomalies.dag/unknown-deps` contracts in `[:error :data :anomaly]`.

## Validation

- `clojure -M:dev:test` focused planner DAG activation tests.
- Exceptions-as-data scanner reports no cleanup row for
  `phase_software_factory/plan.clj`.
