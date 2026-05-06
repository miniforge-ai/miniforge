## Summary

This PR packages the Claude dogfood fixes that are not review-policy specific:

1. short backend/version probes in CLI startup could still behave differently from the real inherited Claude process
2. a valid `:already-implemented` implement result could still be overwritten by curator `:no-files-written`
3. workflow resume could still die when a checkpoint recorded a synthetic DAG task workflow id instead of a loadable
  top-level workflow type

## What Changed

### CLI startup probes are supervised directly

- replace the short probe path in `workflow_runner.clj` with direct `ProcessBuilder` execution
- read stdout/stderr concurrently
- close stdin explicitly
- enforce timeout with forcible process teardown
- add focused probe tests for:
  - fast successful command capture
  - bounded timeout behavior

### Implement preserves verified no-op outcomes

- in `enter-implement`, a normalized `:already-implemented` result now survives curator `:curator/no-files-written`
- this keeps verified “task already satisfied” outcomes from being collapsed into terminal empty-diff failures
- existing repair-loop hardening remains in place:
  - unsupported `:already-implemented` after prior review/verify failures still fails closed
- add a regression covering the full `enter`/`leave` path for a legitimate already-implemented no-op

### Resume ignores synthetic DAG task workflow ids

- `workflow-resume` now treats keywords like `:dag-task-...` as internal synthetic workflow ids, not loadable workflow
  types
- identity resolution now prefers:
  - recorded workflow spec
  - non-synthetic machine snapshot workflow id
  - fallback selection profile
- this fixes resume runs that previously died with `Workflow not found` while replaying task-subworkflow checkpoints
- add a regression for the synthetic DAG-task id shape

## Validation

- `clj-kondo` on touched files: clean
- focused JVM regression run:
  - `ai.miniforge.phase-software-factory.implement-test`
  - `ai.miniforge.workflow-resume.core-test`
  - `ai.miniforge.cli.workflow-runner.preflight-test`

## Live Behavior

- Claude dogfood on this branch now stamps `/Users/chris/.local/bin/claude 2.1.129`
- `:explore` passes
- `:plan` passes
- the first DAG implement/verify cycle gets past startup and no-op handoff issues
