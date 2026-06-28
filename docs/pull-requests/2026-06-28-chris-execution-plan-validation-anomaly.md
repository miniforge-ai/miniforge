<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Execution Plan Validation Anomaly

Branch: `chris/execution-plan-validation-anomaly`

## Summary

- Return canonical `:anomalies/incorrect` data from
  `create-execution-plan` when a plan fails schema validation.
- Preserve valid execution plan round trips unchanged.
- Keep validation details and the original offending plan on the anomaly map.

## Validation

- `clojure -M:dev:test` focused execution-plan tests: 15 tests, 28 assertions,
  0 failures/errors.
- Exceptions-as-data scanner drops from 143 to 142 cleanup-needed rows; no
  `execution_plan.clj` cleanup row remains.
