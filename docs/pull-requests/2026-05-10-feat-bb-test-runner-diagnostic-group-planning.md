<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(test): add bb-test-runner diagnostic group planning

## Overview

Add the pure grouping and plan-construction helpers for stable-derived
diagnostic runs.

## Why

Once the stable-derived project set is known and the diagnostic options
are parsed, the next seam is deterministic grouping:

- additive expansion to grow the subset quickly
- breadth-first bisect grouping to isolate failures faster
- rendered Poly test steps that higher layers can execute directly

Keeping that logic pure makes the later script/task wiring much smaller
and easier to verify.

## What changed

- add additive expand grouping helpers
- add breadth-first bisect grouping helpers
- add stable-derived diagnostic plan synthesis
- add direct JVM tests for expand, bisect, and rendered plan steps

## Files changed

- `components/bb-test-runner/src/ai/miniforge/bb_test_runner/core.clj`
- `components/bb-test-runner/src/ai/miniforge/bb_test_runner/interface.clj`
- `components/bb-test-runner/test/ai/miniforge/bb_test_runner/core_test.clj`

## Verification

- focused `bb-test-runner.core-test`
- `bb pre-commit`
