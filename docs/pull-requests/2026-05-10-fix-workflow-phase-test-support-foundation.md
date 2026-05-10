<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(test): add workflow phase test-support foundation

## Overview

Introduce the synthetic workflow phase test-support namespace and bind
the phase loader test to the same explicit support resource.

## Why

The narrowed stable-derived test scope exposed that workflow test
support for synthetic phases did not exist as a real namespace in this
branch state. That left later workflow tests depending on ambient phase
registrations instead of an explicit test-only seam.

## What changed

- add `ai.miniforge.workflow.phase-test-support`
- register synthetic `:plan`, `:implement`, `:verify`, and `:done`
  phases for workflow tests
- bind `phase.loader-test` to the explicit test-support resource

## Files changed

- `components/phase/test/ai/miniforge/phase/loader_test.clj`
- `components/workflow/test/ai/miniforge/workflow/phase_test_support.clj`

## Verification

- focused phase loader and workflow phase-support tests
- `bb pre-commit`
