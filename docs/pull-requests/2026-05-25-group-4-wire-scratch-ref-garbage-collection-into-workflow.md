<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# GROUP 4: Wire scratch-ref garbage collection into workflow...

**PR:** [#979](https://github.com/miniforge-ai/miniforge/pull/979)
**Branch:** `mf/group-4-wire-scratch-ref-garbage-collect-644bbfca`

## Summary

GROUP 4: Wire scratch-ref garbage collection into workflow completion. When a workflow emits :workflow/finished (or :workflow/completed), schedule GC of its scratch ref after 7 days. Implement as a...

## Files Changed

- `bases/cli/src/ai/miniforge/cli/workflow_runner/gc_hooks.clj` (create)
- `bases/cli/test/ai/miniforge/cli/workflow_runner/gc_hooks_test.clj` (modify)
- `bases/cli/test/ai/miniforge/cli/workflow_runner/gc_integration_test.clj` (create)
- `components/dag-executor/src/ai/miniforge/dag_executor/scratch_gc_queue.clj` (modify)
- `components/dag-executor/test/ai/miniforge/dag_executor/scratch_gc_queue_test.clj` (create)
- `bases/cli/resources/config/cli/messages/en-US.edn` (modify)
- `bases/cli/src/ai/miniforge/cli/main/commands/workflow_commands.clj` (modify)
- `bases/cli/src/ai/miniforge/cli/workflow_runner.clj` (modify)
- `bases/cli/test/ai/miniforge/cli/main/commands/workflow_commands_test.clj` (modify)
- `bases/cli/test/ai/miniforge/cli/workflow_runner/gc_hooks_test.clj` (modify)
- `components/dag-executor/src/ai/miniforge/dag_executor/interface.clj` (modify)
- `components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/worktree.clj` (modify)
- `components/dag-executor/src/ai/miniforge/dag_executor/scratch_commit.clj` (modify)
- `components/dag-executor/src/ai/miniforge/dag_executor/scratch_gc_queue.clj` (modify)
- `components/dag-executor/test/ai/miniforge/dag_executor/interface_test.clj` (modify)
- `components/dag-executor/test/ai/miniforge/dag_executor/scratch_commit_test.clj` (modify)
- `bases/cli/test/ai/miniforge/cli/workflow_runner/gc_helpers_test.clj` (delete)

## Test Results

_No test artifacts available._

## Review Decision

_No review artifacts available._
