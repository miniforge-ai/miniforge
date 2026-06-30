# Return PR Lifecycle Controller Anomalies

## Summary

`pr_lifecycle/controller.clj` no longer throws for controller transition,
fix-loop budget, or PR-creation anomaly paths. Those paths now return anomaly
data while preserving existing state and history side effects.

## Changes

- Convert invalid controller status transitions from `ex-info` throws to
  returned `:conflict` anomalies.
- Make `update-status!` return transition anomalies without mutating controller
  state.
- Make CI/review fix-loop budget exhaustion return the existing `:conflict`
  anomaly after setting `:failed` and recording history.
- Make `run-lifecycle!` consume PR-creation anomalies as data and return a
  failed lifecycle status map.
- Update PR lifecycle tests to assert returned anomaly contracts.

## Verification

- Focused PR lifecycle controller/anomaly tests:
  63 tests, 164 assertions, 0 failures, 0 errors.
- Exceptions-as-data scanner:
  `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/controller.clj`
  contributes `0` cleanup-needed rows; repository total is `94`.
