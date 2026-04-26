# refactor/dag-snapshot-final

## Summary

Removes the last runtime DAG resume path that reconstructed progress from
workflow event files.

The CLI workflow runner now restores pre-completed DAG task state from the
authoritative workflow checkpoint snapshot only, and the workflow DAG resume
helper no longer falls back to event-file parsing.

## Key Changes

- removed legacy event-file DAG resume logic from
  [dag_resilience.clj](/private/tmp/mf-dag-snapshot-final/components/workflow/src/ai/miniforge/workflow/dag_resilience.clj)
  and replaced it with checkpoint-only `resume-context`
- updated
  [workflow_runner.clj](/private/tmp/mf-dag-snapshot-final/bases/cli/src/ai/miniforge/cli/workflow_runner.clj)
  to resolve the checkpoint-based helper
- renamed the emitted CLI error message key in
  [en-US.edn](/private/tmp/mf-dag-snapshot-final/bases/cli/resources/config/cli/messages/en-US.edn)
- replaced the old event-file-focused tests with checkpoint-authority tests in
  [dag_resilience_resume_test.clj](/private/tmp/mf-dag-snapshot-final/components/workflow/test/ai/miniforge/workflow/dag_resilience_resume_test.clj)

## Validation

- `clj-kondo --lint components/workflow/src/ai/miniforge/workflow/dag_resilience.clj
  bases/cli/src/ai/miniforge/cli/workflow_runner.clj
  components/workflow/test/ai/miniforge/workflow/dag_resilience_resume_test.clj`
- `clojure -M:dev:test -e "(require 'ai.miniforge.workflow.dag-resilience-resume-test)(let [result
  (clojure.test/run-tests 'ai.miniforge.workflow.dag-resilience-resume-test)] (when (pos? (+ (:fail result) (:error
  result))) (System/exit 1)))"`
