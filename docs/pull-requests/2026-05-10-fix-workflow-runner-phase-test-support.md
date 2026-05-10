<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(test): switch workflow runner tests to explicit phase support

## Overview

Make the workflow runner tests consume the explicit synthetic phase
support layer instead of relying on ambient production phase loading.

## Why

Once the synthetic phase support exists, the runner tests still need to
bind to it directly. Without that, narrowed project runs continue to
depend on whatever production phase implementations were loaded first.

## What changed

- update runner-oriented workflow tests to use the synthetic phase set
- bind the phase loader to the test-support resource during those tests
- keep phase-loader state reset between examples

## Files changed

- `components/workflow/test/ai/miniforge/workflow/runner_test.clj`
- `components/workflow/test/ai/miniforge/workflow/runner_extended_test.clj`
- `components/workflow/test/ai/miniforge/workflow/runner_iteration_test.clj`

## Verification

- focused workflow runner tests under narrowed project scope
- `bb pre-commit`
