<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(test): add stable-derived planning helpers to bb-test-runner

## Overview

Add pure stable-derived planning helpers to `bb-test-runner` so the
test-scope logic can be exercised under JVM tests and reused by the
Babashka wrapper.

## Why

The repo needed a real programmable surface for:

- detecting stable tags
- deriving changed-or-affected project queries
- ordering/expanding/bisecting project subsets
- sanitizing leaked git worktree environment
- deriving heartbeat configuration

Without that surface, the wrapper logic stayed opaque and harder to
test directly.

## What changed

- add stable-derived helper functions to `bb-test-runner.core`
- expose the helper surface through `bb-test-runner.interface`

## Files changed

- `components/bb-test-runner/src/ai/miniforge/bb_test_runner/core.clj`
- `components/bb-test-runner/src/ai/miniforge/bb_test_runner/interface.clj`

## Verification

- helper-focused `bb-test-runner` tests
- `bb pre-commit`
