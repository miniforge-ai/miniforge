<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor(web-dashboard): externalize deployment/session knobs to EDN

## Overview

Moves the web-dashboard's per-deployment and session-policy constants out of
inline code literals and into a single EDN resource, following the
config-as-data pattern (Dewey 007) already used by the reliability component.

## Motivation

The HTTP port, session cookie name, session TTL, stale-workflow threshold, and
recent-workflow cap were hard-coded as literals across three source files.
These are deployment and policy knobs, not algorithm constants, so they belong
in data rather than in code.

## Changes

- Add `components/web-dashboard/resources/config/web-dashboard/defaults.edn`:
  one non-namespaced map with `:port`, `:session-cookie-name`,
  `:session-ttl-ms`, `:stale-threshold-ms`, and `:max-recent-workflows`.
  Computed integer values carry `;; N` comments showing the original
  arithmetic.
- `server.clj`: load defaults via `io/resource` + `edn/read-string`; the
  `start-server!` `:or` default for `:port` now reads `(:port defaults)`.
- `server/auth.clj`: `default-cookie-name` and `default-session-ttl-ms` now
  source their values from the EDN map. The named fallback constants are kept.
- `state/workflows.clj`: `stale-threshold-ms` and `max-recent-workflows` now
  source their values from the EDN map. The named constants are kept.

Behavior-preserving: every loaded value is identical to the prior literal.
`control_plane.clj` heartbeat (30000) is intentionally left in place.

## Verification

- Load smoke check: each value read from the EDN equals its original literal
  (`:port` 7878, `:session-cookie-name` "miniforge-dashboard-session",
  `:session-ttl-ms` 43200000, `:stale-threshold-ms` 600000,
  `:max-recent-workflows` 50).
- `bb poly:check` from the workspace root: OK.
- Isolated test-runner under minimal `-Sdeps` could not resolve the
  pre-existing transitive `control-plane` dependency; the full pre-commit gate
  is authoritative for the test suite.
