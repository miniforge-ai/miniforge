<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Connector HTTP Input Anomalies

Branch: `chris/connector-http-input-anomalies`

## Summary

- Return canonical anomaly maps from HTTP connector `connect`, `discover`, and
  `extract` implementation boundaries for invalid input, unknown handles, and
  upstream request failures.
- Preserve the explicit `request/throw-on-failure!` compatibility helper for
  callers that still opt into exception escalation.
- Document `discover` as an anomaly-capable connector protocol result.

## Validation

- `clojure -M:dev:test` focused HTTP connector tests: 33 tests, 107 assertions,
  0 failures/errors.
- Exceptions-as-data scanner drops from 146 to 143 cleanup-needed rows on this
  branch; only the explicit HTTP request throwing helper remains in
  `connector_http`.
