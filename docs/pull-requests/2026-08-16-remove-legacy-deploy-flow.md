<!--
  Title: Remove the superseded deployment application flow
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(deploy): remove legacy application flow

## Layer

Application flow and its tests.

## Overview

Remove `deploy-flow`, the direct rollback-then-apply path superseded when
PR 1794 made `deploy-governed` the only live deployment application flow.

## Changes

- Delete the unreferenced legacy flow namespace and its obsolete behavior
  tests.
- Retain the provider observation and phase evidence regressions by moving
  them into the existing deployment test namespace.
- Centralize the relocated target, rollback, and outcome fixtures as values.

The now-unreferenced provider convenience function remains for a separate
provider-layer cleanup so this PR stays within the application-flow stratum.

## Verification

- No production or test reference to `deploy-flow` remains.
- `clojure -M:poly test brick:phase-deployment`
- `bb pre-commit`
- `bb review`
