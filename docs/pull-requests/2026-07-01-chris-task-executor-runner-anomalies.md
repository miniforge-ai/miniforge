<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Refactor: return task executor runner failures as data

## Overview

This PR migrates normal task-executor runner failure paths away from thrown
exceptions and into DAG result data.

## Motivation

`task-executor.runner` still throws for worktree acquisition, environment
acquisition, and spec-validation failures. Those are expected orchestration
outcomes, not process-boundary failures, so they should flow as data and let
the runner mark the task failed without stack unwinding.

## Changes in Detail

- Replace acquisition throws with typed DAG errors.
- Replace spec-validation throws with a typed DAG error result.
- Keep the outer exception catch for unexpected exceptions from effects.
- Update runner tests to assert data-returning failure paths.

## Testing Plan

- Run focused task-executor runner tests.
- Run the exceptions-as-data scanner for `task_executor/runner.clj`.
- Run `bb pre-commit`.

## Deployment Plan

No deployment special handling. The public runner surface remains the same
success/failure map shape from `execute-task`.

## Related Issues/PRs

- Continues the exceptions-as-data cleanup wave after #1337.
- Independent of #1338.

## Checklist

- [x] Focused task-executor tests pass.
- [x] `task_executor/runner.clj` scanner cleanup-needed count reaches zero.
- [x] `bb pre-commit` passes.
