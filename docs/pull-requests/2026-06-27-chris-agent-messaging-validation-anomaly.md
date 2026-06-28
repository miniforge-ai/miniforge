<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Agent Messaging Validation Anomaly

## Summary

- Return `:anomalies/incorrect` data when outbound inter-agent message
  validation fails.
- Keep valid message routing and event emission behavior unchanged.
- Add protocol coverage that invalid outbound messages are not routed.

## Validation

- `clojure -M:dev:test` focused agent messaging tests.
- Exceptions-as-data scanner reports no cleanup row for
  `agent/protocols/impl/messaging.clj`.
