<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# test(bb): cover stable-derived bb-test-runner helpers

## Overview

Add direct JVM coverage for the new stable-derived `bb-test-runner`
helper surface, starting with the pure parsing and ordering seams.

## Why

The new stable-derived planning path only pays off if it stays
provably deterministic. The helper layer is pure enough to test without
spawning real Babashka tasks, so this PR starts by locking down the
option parsing and project ordering logic directly.

## What changed

- add helper-level tests for stable-tag handling
- add project selector parsing/formatting coverage
- add diagnostic arg parsing coverage
- add project ordering coverage
- add git worktree env sanitization coverage
- add heartbeat config coverage

## Files changed

- `components/bb-test-runner/src/ai/miniforge/bb_test_runner/core.clj`
- `components/bb-test-runner/src/ai/miniforge/bb_test_runner/interface.clj`
- `components/bb-test-runner/test/ai/miniforge/bb_test_runner/core_test.clj`

## Verification

- focused `bb-test-runner.core-test`
- `bb pre-commit`
