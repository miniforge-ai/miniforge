<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Fix: Return R2 pull failures as anomalies

## Overview

This PR converts `bb-r2/pull!` failure branches from thrown exceptions to
canonical anomaly values. The helper already returns tagged outcomes for
successful and missing-object cases; this change makes command/config failures
data-shaped too.

## Motivation

The exceptions-as-data cleanup scan still reports a `bb-r2` normal-flow throw.
`pull!` is small, has injected process tests, and has no in-repo production
callers beyond the public pass-through interface, making it a contained
remediation wave.

## Changes in Detail

- Add the anomaly component to `bb-r2`.
- Return `:invalid-input` anomalies when required pull options are missing.
- Return a `:fault` anomaly when `wrangler r2 object get` fails for reasons
  other than a missing key.
- Update interface docs and focused tests for the anomaly-returning cases.

## Testing Plan

- Run focused `bb-r2` tests:
  `clojure -M:dev:test -e "(require 'ai.miniforge.bb-r2.core-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.bb-r2.core-test)"`.
  Latest result: 13 tests, 23 assertions, 0 failures.
- Run `bb review` and confirm `bb-r2` contributes zero cleanup-needed rows.
  Latest scanner count: 159 cleanup-needed rows.
- Run `bb pre-commit`.

## Deployment Plan

No deployment steps. This is a Babashka helper behavior cleanup.

## Related Issues/PRs

- Follows PR #1277.

## Checklist

- [x] Implementation updated.
- [x] Tests updated and focused suite passes.
- [x] `bb review` count reduced.
- [x] `bb pre-commit` passes.
- [ ] PR opened, comments resolved, CI green, and merged.
