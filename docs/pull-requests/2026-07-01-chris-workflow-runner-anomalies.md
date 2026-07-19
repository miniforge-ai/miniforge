<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: return workflow runner failures as data

## Summary

Convert the workflow runner's local cleanup-needed exception paths to
context/result data.

## Changes

- Dashboard stop control flow now returns a canonical stop anomaly and marks
  the workflow context failed instead of throwing through the loop.
- Initial context anomalies now become failed execution contexts from
  `run-pipeline`.
- Synthetic cleanup exceptions are replaced with map-shaped failure data, and
  cleanup signal construction accepts either maps or throwables.
- Terminal initial contexts now bypass empty-pipeline failure handling to avoid
  double-failing an already terminal context.

## Validation

- Focused runner tests:
  `ai.miniforge.workflow.runner-test`,
  `ai.miniforge.workflow.anomaly.build-initial-context-test`
- Targeted `clj-kondo` over edited workflow files and tests.
- Exceptions-as-data scanner: `workflow/runner.clj` now has `0`
  cleanup-needed rows; repository total is `82`.
- `bb pre-commit`
