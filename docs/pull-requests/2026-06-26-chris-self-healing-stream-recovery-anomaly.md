<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Self-Healing Stream Recovery Anomaly Data

## Summary

Convert self-healing stream recovery invalid-input paths from thrown anomalies
to returned anomaly maps.

## Changes

- Return `:anomalies/incorrect` maps from backend binary resolution guards.
- Return `:anomalies/incorrect` maps from `evaluate-stall-recovery` guard
  clauses for invalid backend or hang-count input.
- Propagate invalid backend anomalies from `execute-resume!` before process
  startup.
- Update stream recovery tests to assert returned anomaly data.

## Validation

- `clojure -M:dev:test -e "(require 'ai.miniforge.self-healing.stream-recovery-test
  'ai.miniforge.self-healing.anomaly.stream-recovery-anomaly-test 'clojure.test) (clojure.test/run-tests
  'ai.miniforge.self-healing.stream-recovery-test 'ai.miniforge.self-healing.anomaly.stream-recovery-anomaly-test)"`
- Exceptions-as-data scanner: no `self_healing/stream_recovery` cleanup-needed
  rows.
