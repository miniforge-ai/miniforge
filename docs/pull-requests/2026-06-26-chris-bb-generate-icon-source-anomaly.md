# Fix: Return icon source misses as anomalies

## Overview

This PR converts the `bb-generate-icon` missing source PNG branch from a thrown
exception to a canonical anomaly value. Existing icon generation behavior is
unchanged when the source exists or a placeholder is configured.

## Motivation

The exceptions-as-data cleanup scan reports one normal-flow throw in
`bb-generate-icon`. A missing source path is invalid input to the helper, not a
process-fatal exception, and can be represented as data before the side-effecting
icon generation steps run.

## Changes in Detail

- Add the anomaly component to `bb-generate-icon`.
- Return an anomaly map with `:anomaly/type :invalid-input` when no source icon
  exists and no placeholder is configured.
- Short-circuit `run!` when source resolution returns an anomaly.
- Add focused coverage for existing source and missing-source resolution.

## Testing Plan

- Run focused `bb-generate-icon` tests.
  Latest result: 7 tests, 13 assertions, 0 failures.
- Run the exceptions-as-data scanner and confirm `bb-generate-icon` contributes
  zero cleanup-needed rows.
  Latest scanner count: 156 cleanup-needed rows; `bb-generate-icon`
  contributes zero rows.
- Run `bb pre-commit`.
  Latest result: all checks passed.

## Deployment Plan

No deployment steps. This is a Babashka helper input-validation cleanup.

## Related Issues/PRs

- Follows PR #1280.

## Checklist

- [x] Implementation updated.
- [x] Tests updated and focused suite passes.
- [x] `bb review` count reduced.
- [x] `bb pre-commit` passes.
- [ ] PR opened, comments resolved, CI green, and merged.
