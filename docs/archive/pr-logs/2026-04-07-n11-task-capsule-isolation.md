<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N11 Task Capsule Isolation — Implementation

**PR:** feat/n11-task-capsule-isolation
**Date:** 2026-04-07
**Spec:** `specs/normative/N11-task-capsule-isolation.md`

## Summary

Closes the gap between the N11 normative spec and the codebase. In governed
mode (`:execution/mode :governed`), the agent process, MCP server, governance
hooks, test execution, and git commands now run **inside** the task capsule
(Docker/K8s container) instead of on the host.

Local mode (`:execution/mode :local`, the default) is completely unchanged.

## What Changed

### M0: Environment config pass-through (`runner.clj`)

The runner extracted `repo-url` and `branch` from workflow opts but never
forwarded them to the executor. Now `build-env-record` accepts an `env-config`
map and passes it to `acquire-environment!`.

### M1: Docker workspace bootstrap (`docker.clj`)

Added `bootstrap-workspace!` — after container creation, when `:repo-url` is
present in env-config, the Docker executor runs `git clone` and configures git
identity inside the container. No-op when `:repo-url` is nil (backward
compatible).

### M2: Capsule-aware exec-fn (`llm_client.clj`, `records/llm_client.clj`, `runner.clj`)

- Added `capsule-exec-fn` factory that routes CLI commands through
  `executor-execute!` instead of host `babashka.process/shell`.
- Made `stream-exec-fn` injectable via a new `:stream-exec-fn` field on the
  `CLIClient` record. `handle-streaming` checks the client field before
  falling back to the module-level function.
- Runner builds and attaches the capsule exec-fn to context as
  `:execution/exec-fn` when governed mode is active.

### M3: Capsule-aware MCP session and hooks (`artifact_session.clj`)

Added capsule-aware session lifecycle:

- `create-capsule-session!` — creates session dir inside the capsule via
  `executor-execute!`
- `write-capsule-mcp-config!` — writes MCP config and Claude settings into
  the capsule workspace; server command resolves to capsule-local `bb`
- `read-capsule-artifact` — reads artifact EDN back from the capsule
- `cleanup-capsule-session!` — removes session dir inside capsule
- `with-capsule-artifact-session` macro — full lifecycle for governed mode

### M4: Capsule-aware verify phase (`verify.clj`)

Added `run-tests-in-capsule!` that routes test commands through
`executor-execute!`. `enter-verify` dispatches to capsule or host path based
on `:execution/mode`.

### M5: Capsule-aware release phase (`release.clj`)

Added `git-dirty-files-capsule` that runs `git status` and `cat` inside the
capsule via `executor-execute!`. Both `build-workflow-state` and the
`enter-release` short-circuit check dispatch to capsule or host path based on
`:execution/mode`.

## Files Changed (7)

| File | Change |
|------|--------|
| `components/workflow/src/.../runner.clj` | Wire env-config through; build capsule exec-fn |
| `components/dag-executor/src/.../docker.clj` | `bootstrap-workspace!` for git clone inside container |
| `components/llm/src/.../impl/llm_client.clj` | `capsule-exec-fn` factory; injectable `stream-exec-fn` |
| `components/llm/src/.../records/llm_client.clj` | Add `:stream-exec-fn` field to `CLIClient` |
| `components/agent/src/.../artifact_session.clj` | Capsule session lifecycle (create, config, read, cleanup) |
| `components/phase-software-factory/src/.../verify.clj` | `run-tests-in-capsule!` |
| `components/phase-software-factory/src/.../release.clj` | `git-dirty-files-capsule`; mode-aware dispatch |

## Design Decisions

1. **All changes gated on `:execution/mode :governed`** — local mode paths
   are untouched. Zero risk to existing workflows.

2. **Batch-first for streaming** — N11 \u00a76.5 says non-streaming invocation
   via `executor-execute!` is conformant. Streaming through `docker exec` is
   a performance enhancement, not a correctness requirement.

3. **No protocol changes** — `TaskExecutor` already has `execute!`,
   `copy-to!`, `copy-from!` which cover all capsule operations.

4. **`requiring-resolve` for cross-component calls** — Avoids adding
   dag-executor as a dependency of phase-software-factory. The protocol
   dispatch happens on the executor object already in context.

## Test Results

610 tests, 2654 passes, 0 failures, 0 errors across all changed bricks
(workflow, dag-executor, llm, agent, phase-software-factory).
