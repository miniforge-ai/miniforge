# Fix: Return missing control-plane agent transitions as anomalies

## Overview

This PR converts the `control-plane` registry lookup miss in
`transition-agent!` from a thrown exception to a canonical anomaly value.
Successful transitions and invalid state-machine transitions keep their existing
behavior.

## Motivation

The exceptions-as-data cleanup scan reports the missing-agent branch in
`transition-agent!`. A missing agent ID is a caller-visible lookup miss, not a
process-fatal exception, and can be represented as data without changing valid
transition behavior.

## Changes in Detail

- Add the anomaly component to `control-plane`.
- Return an anomaly map with `:anomaly/type :not-found` when
  `transition-agent!` cannot find the agent ID.
- Update registry/interface docs for the new missing-agent return contract.
- Add focused public API coverage for the missing-agent anomaly branch and the
  concurrent-removal race where `update-agent!` returns nil.

## Testing Plan

- Run focused `control-plane` interface tests.
  Latest result: 14 tests, 77 assertions, 0 failures.
- Run the exceptions-as-data scanner and confirm `control-plane/registry`
  contributes zero cleanup-needed rows.
  Latest scanner count: 157 cleanup-needed rows; `control-plane/registry`
  contributes zero rows.
- Run `bb pre-commit`.
  Latest result: all checks passed.

## Deployment Plan

No deployment steps. This is a control-plane registry lookup failure cleanup.

## Related Issues/PRs

- Follows PR #1279.

## Checklist

- [x] Implementation updated.
- [x] Tests updated and focused suite passes.
- [x] `bb review` count reduced.
- [x] `bb pre-commit` passes.
- [ ] PR opened, comments resolved, CI green, and merged.
