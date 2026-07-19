<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: externalize self-healing backend-failover policy to EDN

## Overview

Config-as-data (Dewey 007) gap fix for `self-healing` — the standard's own
headline example ("self-healing thresholds / cooldowns / backend
allow-list"). The backend allow-list, failover order, default backend, and
the four health-tuning constants lived as `def` literals in
`backend_health.clj`, with the threshold and cooldown re-hardcoded a second
and third time as inline fallbacks in `stream_recovery.clj` and
`integration.clj`. The component had no `resources/config/` at all.

## Motivation

The set of failover backends and their order is operator policy — an
operator reorders or restricts failover, or retunes the health gate,
without a code release (signals 1 and 2). The threshold, cooldown, recency
window, and decay age compose as one failover policy block (signal 3). The
same two numbers living in three source files is the standard's
"configuration spread across multiple defs" smell.

## Changes

- **New** `resources/config/self-healing/backend-health.edn` — one map:
  `:success-rate-threshold`, `:switch-cooldown-ms`,
  `:failure-recency-window-ms`, `:health-decay-ms`, `:default-backend`,
  `:fallback-order`.
- **`backend_health.clj`** — loads the EDN into a public `config`; the four
  named `default-*` constants and `decay-threshold-ms` now read their value
  from it (keeping the named constant as the in-code fallback per the
  standard), and `default-health-data` pulls `:default-backend` /
  `:fallback-order` from it.
- **`stream_recovery.clj`** / **`integration.clj`** — the inline `0.90` /
  `1800000` fallbacks now resolve from `backend-health/config`, so the three
  sites share one source of truth instead of three copies.

## Verification

- self-healing tests (`backend_health`, `stream_recovery`,
  `workaround_*`, anomaly): 62 tests, 127 assertions, 0 failures.
- Load smoke: `config` resolves; `default-health-data` carries the prior
  default-backend / fallback-order.
- `bb poly:check`: OK.
