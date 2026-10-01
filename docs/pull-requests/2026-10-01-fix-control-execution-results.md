<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: preserve structured control failures

## Overview

Keep failed governed interventions failed through HTTP, control events and N6
evidence. Base: `fix/evidence-control-records`; depends on #1969 for the real
dashboard requester and canonical evidence projection. Retarget main before merge.

## Motivation

The dashboard discarded returned failures, and control execution then wrapped
every return value in success. This made an unwritten intervention look executed.

## Changes in Detail

- Separate result normalization from execution orchestration, keeping namespaces stratified.
- Preserve response failures and anomaly details; wrap only unstructured successes.
- Keep exception failures, interrupt signals, and fatal-error propagation at the invocation boundary.
- Report failed dashboard controls as HTTP failures and retain failure evidence.
- Localize touched execution and dashboard diagnostics.
- Record malformed-action denials without crashing the event description builder;
  preserve malformed input in the audit record and never invoke the executor.

## Testing Plan

Test returned failures, anomalies, exceptions, success wrapping, denied effects,
and actual dashboard-to-event-to-evidence failure delivery. Run affected consumer
suites serially, rebuild the packaged CLI, and run the adversarial standards pass.

All four event-stream consumer suites and the dashboard suite pass in serial.
Expanded focused JVM and rebuilt packaged CLI regressions pass nine tests and
92 assertions, including ordinary exceptions, thrown anomalies, and nil/false
success payloads. Kondo and inferred namespace strata pass.
The approval-alias integration and shared anomaly predicate are verified.
JVM and rebuilt packaged tests pass 11 tests and 120 assertions.
These include legacy anomaly returns from the intervention producer.
After full prerequisite integration, all four event-stream consumers and the
dashboard suite pass again. Rebuilt packaged failure and malformed-dispatch
regressions pass 10 tests and 108 assertions. Kondo and inferred strata are clean.
Interruption and startup-boundary regressions pass 14 tests and 90 assertions
on the JVM and rebuilt packaged CLI; the worker retains its interrupt signal.

## Deployment Plan

No external deployment. Merge only after dependency, clean current-head review,
resolved comments, all CI checks, and conflict resolution.

## Scope

This does not complete N3 durable emission or N8 approval/state snapshot capture.
