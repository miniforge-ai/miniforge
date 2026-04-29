# Fix Dogfood Planner And Worktree Materialization

## Summary

This PR fixes the next set of dogfood blockers after `#676`:

- planner sessions now preload `:task/existing-files` into the artifact-session context cache
- planner honors a submitted MCP plan artifact even if the CLI later classifies the request as failed
- spec workflow input now promotes `:files-in-scope` to the runtime boundary
- the `:explore` phase reads `files-in-scope` from the nested execution context shape
- `miniforge run --worktree` now materializes a real detached git worktree instead of only threading a path string
  through execution

## Why

Dogfooding exposed two remaining blockers after the first isolation/event fixes:

1. the planner still timed out with no output because it was not using the same artifact-session context cache path as
  the implementer/tester
2. even with the right runtime worktree key, `--worktree` still pointed the workflow at an empty directory, so
  `:explore` had no repository files to load

This slice fixes both seams so the next dogfood run executes against a real checkout with actual scoped files.

## Validation

- `clj-kondo --lint` on all touched production and test files
- `bb test bases/cli components/phase-software-factory components/agent`
- full `bb pre-commit`

## Follow-up

- rerun the behavioral-verification dogfood workflow from a fresh execution worktree on this committed branch
- if the planner still stalls after these fixes, the next blocker is no longer worktree/context setup and can be
  debugged directly in the planner/LLM path
