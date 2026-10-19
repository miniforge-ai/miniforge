<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Propagate phase publication failures

## Purpose and layer

Workflow application integration for N3 section 9.1: current-profile phase
publication failures must reach the execution boundary, not disappear behind
best-effort printing, heartbeats, or user observer callbacks.

## Dependencies and scope

Based on main, including merged runner integration #2050 and callback refusal
execution #2051. Requires executing-agent facts #2052 before merge. Preserve legacy transport and nil-stream
observer compatibility. This does not migrate the default journal or supply
missing workflow intent, phase artifacts, or terminal evidence.

## Changes

- Preserve current publication anomalies and convert unexpected nonfatal transport
  exceptions into anomaly values; retain legacy best-effort behavior.
- Propagate phase publisher failures through callbacks before subsequent effects.
- Stop completion heartbeats even when the completion publication fails.
- Restore thread interruption after constructing a current-publication anomaly.

## Verification

All three product workflow suites passed serially (1m27s). The isolated rebuilt
CLI passed 17 tests and 103 assertions covering real closed journals, invalid
drafts, durable reopen, nonfatal exceptions, interruption, callback ordering,
legacy compatibility, and the execution refusal boundary. Inferred strata are
unchanged and lint reports zero warnings or errors.

The interruption test captures the flag before assertions: an isolated diagnostic
confirmed Clojure's bound test report counter clears it during assertion reporting.
Final composed acceptance must retain #2052's executing-agent projection when the
dependency is integrated; no current-host completeness claim is made here.

## Deployment and gates

Normal CLI build, no data migration. Reverting restores the prior best-effort
phase callback behavior. Normal commit budgets, signed hooks, standards review,
exact-head Copilot review, and all CI gates remain required.
