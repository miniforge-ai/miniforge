<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Refactor DAG Resume To Machine Snapshot Convergence

## Summary

- make checkpointed machine snapshots carry the DAG pause/resume state
  needed for resume, instead of depending on event replay as the primary
  source of truth
- reconstruct completed DAG task ids, recovered DAG artifacts, and DAG
  pause metadata from checkpointed machine state in
  `workflow-resume`
- thread recovered DAG artifacts through the CLI resume path so resumed
  configurable workflows preserve prior DAG outputs
- keep legacy event-file reconstruction only as a fallback compatibility
  path

## Implementation Notes

- paused DAG results now record completed task ids, pause reason,
  wait metadata, and recovered artifacts in
  [components/workflow/src/ai/miniforge/workflow/dag_orchestrator.clj](/components/workflow/src/ai/miniforge/workflow/dag_orchestrator.clj)
- failed DAG execution stores the DAG result on the execution context so
  checkpoint persistence can capture it in
  [components/workflow/src/ai/miniforge/workflow/execution.clj](/components/workflow/src/ai/miniforge/workflow/execution.clj)
- checkpoint persistence now keeps `:execution/dag-result` in the durable
  machine snapshot in
  [components/workflow/src/ai/miniforge/workflow/checkpoint_store.clj](/components/workflow/src/ai/miniforge/workflow/checkpoint_store.clj)
- `workflow-resume` prefers checkpointed DAG state and only falls back to
  event replay when the snapshot lacks DAG resume data
- the CLI resume command now passes recovered DAG artifacts through
  `:pre-completed-artifacts`

## Validation

- `clj-kondo --lint` on the touched workflow, workflow-resume, and CLI files
- `clojure -M:dev:test -e "(require '[clojure.test :as t]`
  `'ai.miniforge.workflow.dag-resilience-execution-test`
  `'ai.miniforge.workflow.dag-resilience-resume-test`
  `'ai.miniforge.workflow.checkpoint-store-test`
  `'ai.miniforge.workflow-resume.core-test`
  `'ai.miniforge.cli.main.commands.resume-test) ..."`
- `bb test components/workflow components/workflow-resume bases/cli`
- `bb pre-commit`
