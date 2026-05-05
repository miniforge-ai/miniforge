## Summary

This PR fixes the Claude dogfood regression where planner exploration could start from the execution worktree instead of
the actual source repository.

That made the MCP context server explore the wrong tree, which is exactly the kind of mistake that can silently produce
greenfield planning or wrong-base edits.

## What Changed

### Source root is now distinct from execution worktree

- keep `:spec/source-dir` anchored to the spec file instead of aliasing it to `--worktree`
- derive and preserve a canonical `:source-root` in CLI runtime context
- pass `:source-root` through workflow context into nested agent execution
- thread `--source-root` into artifact sessions and the MCP context server

### MCP filesystem fallback now reads from the real repo

- context cache state stores `:source-root`
- filesystem fallback reads, grep, glob, and cache writes resolve relative to that root
- generated MCP session config now tells the context server both:
  - artifact dir
  - source root

### Startup provenance is louder and stricter

- print `Source`, `Worktree`, `Branch`, `Commit`, and `Upstream` before execution
- warn when the source checkout is detached or dirty
- fail closed if:
  - the source root is not a git checkout
  - the explicit execution worktree was lost before runtime
  - the spec source dir no longer aligns with the resolved source root

## Validation

- `clj-kondo` on touched CLI, workflow, agent, and MCP context files
- focused tests:
  - `ai.miniforge.cli.main.commands.run-test`
  - `ai.miniforge.cli.workflow-runner.context-test`
  - `ai.miniforge.cli.workflow-runner.preflight-test`
  - `ai.miniforge.workflow.runner-test`
  - `ai.miniforge.mcp-context-server.context-cache-test`
  - `ai.miniforge.agent.artifact-session-extended-test`
- full `bb pre-commit`

## Live Result

- live dogfood confirmed generated session config now includes `--source-root`
- planner exploration used MCP context tools against the source repo instead of the execution worktree

## Follow-up

The next PR in the stack adds backend startup preflight and tighter Claude CLI timeout handling so unhealthy
non-interactive backends fail early instead of drifting into long silent waits.
