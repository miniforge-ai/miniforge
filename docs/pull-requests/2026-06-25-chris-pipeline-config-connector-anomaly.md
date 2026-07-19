<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Fix: Return connector registry misses as anomalies

## Overview

This PR converts the `pipeline-config` connector registry missing-type branch
from a thrown exception to a canonical anomaly value. Successful connector
instantiation keeps the existing `{:connector-refs ... :connectors ...}` shape.

## Motivation

The exceptions-as-data cleanup scan reports a normal-flow throw in
`instantiate-connectors`. Missing connector types are caller-visible lookup
misses, not process-fatal exceptions, and the component already has focused
public API tests around that branch.

## Changes in Detail

- Add the anomaly component to `pipeline-config`.
- Return a `:not-found` anomaly when a symbolic connector ref names an
  unregistered connector type.
- Update the public interface docstring to name the anomaly return.
- Update the connector registry missing-type test to assert anomaly data.

## Testing Plan

- Run focused `pipeline-config` tests.
  Latest result: 23 tests, 56 assertions, 0 failures.
- Run the exceptions-as-data scanner and confirm `pipeline-config` contributes
  zero cleanup-needed rows.
  Latest scanner count: 158 cleanup-needed rows; `pipeline-config` contributes
  zero rows.
- Run `bb pre-commit`.
  Latest result: all checks passed.

## Deployment Plan

No deployment steps. This is a data-contract cleanup for pipeline config
registry lookup failures.

## Related Issues/PRs

- Follows PR #1278.

## Checklist

- [x] Implementation updated.
- [x] Tests updated and focused suite passes.
- [x] `bb review` count reduced.
- [x] `bb pre-commit` passes.
- [ ] PR opened, comments resolved, CI green, and merged.
