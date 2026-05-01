<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: clean up remaining lightweight implicit state machines

## Overview

This slice formalizes three small lifecycle/state-transition areas that were
still using ad hoc or derived transition logic:

- heuristic lifecycle promotion/deprecation
- connector retry circuit-breaker transitions
- loop outer-phase advancement and rollback

The public API shape stays stable. The change is aimed at making legal and
forbidden transitions explicit, testable, and authoritative.

## Motivation

The repo now has a clear direction toward explicit lifecycle authority. These
three areas were still lightweight hand-rolled state machines with either:

- implicit transition rules in conditionals
- derived state that was not backed by an explicit transition model
- vestigial transition helpers based on list/index arithmetic

This cleanup makes those transition rules concrete without widening scope into
neighboring subsystems.

## Changes

| Area | Change |
|------|--------|
| `heuristic/lifecycle` | Back lifecycle transitions with the shared `fsm` component; preserve `valid-transition?` and `transition` API |
| `connector-retry/circuit-breaker` | Replace implicit open/half-open/closed behavior with explicit transition helpers and timeout normalization |
| `loop/outer` | Replace phase-index/next-phase authority with a compiled phase FSM and explicit rollback events |
| Tests | Add focused tests for allowed and forbidden transitions in each owned area |
| Component deps | Declare direct local deps required by the new FSM-backed implementations and existing loop requires |

## Testing

- `clj-kondo --lint`
  `components/heuristic/src/ai/miniforge/heuristic/lifecycle.clj`
  `components/heuristic/test/ai/miniforge/heuristic/lifecycle_test.clj`
  `components/connector-retry/src/ai/miniforge/connector_retry/circuit_breaker.clj`
  `components/connector-retry/test/ai/miniforge/connector_retry/circuit_breaker_test.clj`
  `components/loop/src/ai/miniforge/loop/outer.clj`
  `components/loop/test/ai/miniforge/loop/outer_test.clj`
- `clojure -M:test` from `components/heuristic` for `ai.miniforge.heuristic.core-test` and
  `ai.miniforge.heuristic.lifecycle-test`
- `clojure -M:test` from `components/connector-retry` for `ai.miniforge.connector-retry.interface-test` and
  `ai.miniforge.connector-retry.circuit-breaker-test`
- `clojure -M:test` from repo root for `ai.miniforge.loop.outer-test`
- `clojure -M:test` from repo root for `ai.miniforge.loop.metrics-accumulation-test`

## Notes

- A broader `bb test` run from this shared branch still expands to unrelated
  dirty bricks and hits a pre-existing syntax error in
  `components/loop/src/ai/miniforge/loop/inner.clj`, which is outside this
  slice.
- Running loop tests from the component directory still pulls in a pre-existing
  classpath chain that expects additional non-owned dependencies; the owned loop
  namespaces validate from the workspace root.
