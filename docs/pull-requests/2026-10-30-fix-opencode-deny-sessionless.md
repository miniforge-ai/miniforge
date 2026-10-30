<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(llm): OpenCode calls outside an artifact session get every permission denied

## Overview

OpenCode (miniforge's default backend) allows most tools when its config says
nothing, and miniforge passed it no permission config. An OpenCode call outside an
artifact session, which has no miniforge hook, now runs with `--pure` and
`OPENCODE_PURE=1` (no external plugins), with the worktree's own OpenCode config off
(`OPENCODE_DISABLE_PROJECT_CONFIG=1`), as an agent defined in its inline config
(`OPENCODE_CONFIG_CONTENT`) whose permissions deny every tool.

## Motivation

The OpenCode counterpart of #2282 (Claude). A text-only call (the curator, the
reviewer's enumeration retry, scan, the workflow recommender) could edit files or run
Bash under OpenCode's defaults with no check.

## Changes in Detail

1. `client-defaults.edn` `[:opencode :deny-all-permission]`: every OpenCode
   permission key set to `deny`, plus `"*"`.
2. `cli-args/opencode-env`, for a call with no hook settings or one that asks to be
   `:isolated?`: OPENCODE_PURE=1 and OPENCODE_CONFIG_CONTENT. The config is the
   operator's own inline config, if any, with the deny block as its global
   permissions and as the permissions of `isolation-agent`. OpenCode merges config per
   key and applies an agent's rules after the global and project ones, so a global
   block alone could be undone by a repository's `opencode.json` (an extra allow key,
   or a grant on its `build` agent); the agent's final `"*"` deny is not. The agent
   name is random per process, so a repository cannot define one of the same name in
   advance. The worktree's project config is off as well: the name shows in the
   process list, an agent with Bash could then write an `opencode.json` or
   `.opencode/agent/<name>.md` that merges into it, and a project's local MCP servers
   start even under `--pure`.
3. `opencode-args` runs such a call `--pure --agent <isolation-agent>`, ignores the
   request's own `:agent`, and refuses `:attach` (a running server uses its own
   config), `:command` (a configured command can expand shell expressions and name its
   own agent) and `:session`/`:continue?`/`:fork?` (a resumed session's saved
   permission rules apply after the agent's).
4. A backend entry may carry an `:env-fn` (OpenCode does). `complete-impl` passes its
   entries as `:env` exec options; `default-exec-fn` and `start-capture-process!` set
   them on top of the agent environment allowlist; `capsule-exec-fn` runs the capsule
   command under an `env K=V ...` prefix for them, so an executor that ignores an
   `:env` option (Kubernetes) cannot drop them; the backend preflight probe passes them
   too. An operator `OPENCODE_CONFIG_CONTENT` that is not plain JSON (OpenCode also
   reads JSONC) fails the call instead of being dropped.

## Testing Plan

1. `opencode-call-outside-a-session-is-isolated-test`: `--pure --agent
   miniforge-isolated-<hex>`, the request's agent dropped, OPENCODE_PURE=1, deny-all
   global and agent permissions, the operator's inline config kept, `:attach` refused;
   a session call gets none of it.
2. `opencode-call-carries-its-deny-config-test`, `backend-env-entries-reach-the-process-test`,
   `capsule-exec-carries-env-entries-test`; the OpenCode preflight test.
3. OpenCode 1.18.30 `debug agent` / `debug config`, with a project `opencode.json`
   and `.opencode/agent/<name>.md` that name the policy agent and add a local MCP
   server: without `OPENCODE_DISABLE_PROJECT_CONFIG` they turned `task` and `webfetch`
   on and loaded the server; with it, every tool stayed off and no server loaded. A
   project `.opencode/tool/*.ts` that writes a marker on import was imported by
   `opencode run --pure` without the flag and not with it.
4. OpenCode 1.18.30 `debug agent`, with a project `opencode.json` granting
   `"my_mcp_*": "allow"`, `bash` and `edit` (globally and on `build`): the `build` agent
   still had bash, edit and write on under a global-only deny (the earlier version of
   this change); the inline agent had every tool off, its `"*"` deny after the project
   allow.

## Deployment Plan

Ships with the next release. Isolated OpenCode calls, and the backend preflight probe,
no longer read the worktree's `opencode.json`: providers, endpoints and models defined
only there must move to the operator's global OpenCode config
(`~/.config/opencode/opencode.json`) or `OPENCODE_CONFIG_CONTENT`, or the preflight
probe fails before the run starts. OpenCode calls inside an artifact session are unchanged
here; giving them each role's tool permissions is the next change.

## Known limits

1. Upstream OpenCode reports project-local plugins still loading on 1.18.31 and later
   (anomalyco/opencode#49836), with `--pure`, `OPENCODE_PURE=1` and
   `OPENCODE_DISABLE_PROJECT_CONFIG=1` all set. None of the three is a mitigation on
   those versions; such a plugin runs code at startup outside tool permissions, and the
   agent environment allowlist (#2284) only limits what it can read from the
   environment.
2. The operator's global OpenCode config still loads (provider settings); its
   permission rules come before the agent's, so the agent's `"*"` deny still wins.
3. The operator's global OpenCode config is the operator's own and still loads: its
   MCP servers start for an isolated call, and a model set only on its `build` agent
   does not reach the isolation agent (miniforge passes a configured model as
   `--model`).
4. Non-streaming Claude calls through `capsule-exec-fn` lose their stdin prompt (the
   executor has no stdin). That predates this change and belongs with running the
   agent CLI inside the capsule.

## Related Issues/PRs

1. #2282 (Claude isolation), #2284 (agent environment allowlist).

## Checklist

- [x] Tests fail on main and pass here
- [ ] CI green, Copilot comments resolved
