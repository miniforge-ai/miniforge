<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N11 CLI Execution Mode + Capsule Bootstrap Fixes

**PR:** feat/n11-cli-execution-mode
**Date:** 2026-04-07
**Spec:** `specs/normative/N11-task-capsule-isolation.md`

## Summary

Wire `--execution-mode governed` through the CLI so capsule isolation can be
exercised via `bb miniforge run`. Fix workspace bootstrap issues discovered
during dogfooding with real Docker containers.

## What Changed

### CLI Wiring

- `main.clj` — Add `--execution-mode` / `-m` flag to the `run` command spec
- `run.clj` — Forward `:execution-mode` from opts to `run-workflow-from-spec!`
- `workflow_runner.clj` — Thread execution mode from opts or spec into the
  context map that reaches `run-pipeline`

### Capsule Bootstrap Fixes (dogfood findings)

- **HTTPS clone with token auth** — Containers don't have SSH agent access.
  `bootstrap-workspace!` now converts SSH URLs to HTTPS and injects a GitHub
  token from `GH_TOKEN`, `GITHUB_TOKEN`, or `gh auth token`
- **Token sanitization** — Error messages replace `x-access-token:<token>` with
  `x-access-token:***` to prevent credential leaks in logs
- **Local git config** — Use `git -C <workdir> config` instead of `git config
  --global` since container rootfs is read-only
- **tmpfs sizing** — Increase workspace tmpfs from 64MB to 512MB with
  `uid=1000,gid=1000,exec` for proper permissions

### Runner Default Image

- `runner.clj` — Governed mode defaults to `miniforge/task-runner-clojure:latest`
  instead of `alpine:latest` (has git, bb, clj, gh, node)

## Dogfood Results

Ran `bb miniforge run --execution-mode governed` against a structured logging
migration spec. Confirmed:

- Docker containers created with correct image
- Workspace bootstrap clones repo via HTTPS with token auth
- Multiple capsules run in parallel (DAG task parallelism)
- Agent-on-host + commands-in-capsule model works end-to-end
- Plan phase successfully generates task DAG inside capsule environment

## Files Changed (5)

| File | Change |
|------|--------|
| `bases/cli/src/.../main.clj` | Add `--execution-mode` CLI flag |
| `bases/cli/src/.../commands/run.clj` | Forward execution-mode to workflow runner |
| `bases/cli/src/.../workflow_runner.clj` | Thread execution-mode into context |
| `components/dag-executor/src/.../docker.clj` | HTTPS clone, token sanitize, tmpfs fix, local git config |
| `components/workflow/src/.../runner.clj` | Default governed image to task-runner-clojure |
