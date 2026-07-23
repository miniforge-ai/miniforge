<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor(orchestrator,operator): externalize default-config to EDN

## Overview

The orchestrator and operator components each held a `default-config` map as a
code literal in `core.clj`. This change moves that data to EDN resources loaded
at namespace load via `io/resource` + `edn/read-string`, matching the existing
pattern in the reliability component. The `default-config` var name and bound
value are unchanged.

## Motivation

Both maps are operator-tunable policy: token and cost budgets, escalation and
auto-apply thresholds, signal-retention and pattern windows. Config-as-data
(Dewey 007) holds that such policy belongs in a data resource, not embedded as
Clojure literals. The reliability component already loads its defaults from
`resources/config/reliability/defaults.edn`; these two components now follow the
same form.

## Changes

- Added `components/orchestrator/resources/config/orchestrator/defaults.edn`
  with the control-plane defaults (default budget, knowledge-injection and
  learning-capture flags, escalation threshold, log level).
- Added `components/operator/resources/config/operator/defaults.edn` with the
  operator defaults (signal-retention window, pattern window, minimum pattern
  occurrences, auto-apply threshold, shadow period).
- `components/orchestrator/src/ai/miniforge/orchestrator/core.clj`: `default-config`
  now loads from the EDN resource; added `clojure.edn` and `clojure.java.io`
  requires.
- `components/operator/src/ai/miniforge/operator/core.clj`: same change for its
  `default-config`; added the same two requires.

Compile-time arithmetic in the literals was replaced with the computed integer
in EDN, with a comment showing the breakdown:

- orchestrator `:timeout-ms` `(* 30 60 1000)` -> `1800000` (30 min)
- operator `:signal-retention-ms` `(* 24 60 60 1000)` -> `86400000` (24 hours)
- operator `:pattern-window-ms` `(* 60 60 1000)` -> `3600000` (1 hour)
- operator `:shadow-period-ms` `(* 60 60 1000)` -> `3600000` (1 hour)

Both components already listed `resources` on `:paths` in their `deps.edn`; no
path changes were required.

## Verification

- `clojure -M:test -m cognitect.test-runner -d test` (orchestrator): 21 tests,
  79 assertions, 0 failures, 0 errors.
- Same for operator: 68 tests, 202 assertions, 0 failures, 0 errors.
- Load-smoke of each `default-config`: every key and computed millisecond value
  equals the prior literal.
- `bb poly:check`: OK.
