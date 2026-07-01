# Refactor: return planner expected anomalies as data

## Overview

This PR removes the remaining actionable exceptions-as-data sites in
`agent.planner`.

## Motivation

Planner expected-failure paths already construct anomaly data. The final
boundary was still escalating those anomalies through slingshot throws and then
letting the generic agent invoke wrapper convert them back into response maps.
Returning canonical `response/error` values directly preserves the public error
shape without exception control flow.

## Changes in Detail

- Add a planner helper that converts anomaly maps into `response/error` values
  under the existing agent anomaly categories.
- Return error data for missing LLM backend, irreducible context overflow, and
  plan parse-miss paths.
- Preserve submitted-artifact and parseable-stdout success behavior.
- Update planner tests from thrown assertions to canonical error-response
  assertions.

## Testing Plan

- Run focused agent tests.
- Run the exceptions-as-data scanner for `agent/planner.clj`.
- Run `bb pre-commit`.

## Deployment Plan

No deployment special handling. This changes planner expected-failure control
flow while preserving canonical response/error shape.

## Related Issues/PRs

- Continues the exceptions-as-data cleanup waves after #1339 and #1340.

## Checklist

- [x] Focused agent tests pass.
- [x] `agent/planner.clj` scanner cleanup-needed count reaches zero.
- [x] `bb pre-commit` passes.
