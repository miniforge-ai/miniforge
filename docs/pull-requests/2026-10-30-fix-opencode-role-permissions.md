<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(agent,llm): OpenCode sessions get their role's tools as OpenCode permissions

## Overview

OpenCode runs no PreToolUse hook, so miniforge's tool policy never reached an OpenCode
agent session: OpenCode's defaults allowed every role to edit files and run Bash. An
artifact session now hands OpenCode its role's tools as an OpenCode permission block
(`OPENCODE_CONFIG_CONTENT`, with `--pure`): everything denied, then the OpenCode
permissions the role's tools map to allowed, with edits to agent config and `.git`
and a list of remote and credential commands denied inside those.

## Motivation

OpenCode is the default backend. #2291 denies every tool to an OpenCode call outside
a session; this does the same job for session calls by role, so a reviewer on OpenCode
cannot write and only the implementer and tester can run Bash.

## Changes in Detail

1. `tool-policy.edn`: `:opencode/tool-permissions` (Claude tool name to OpenCode
   permission keys; the context MCP tools map to OpenCode's own read/grep/glob/list and
   edit, since OpenCode sessions get no context server), `:opencode/edit-deny`,
   `:opencode/bash-deny`; added to the closed schema.
2. `tool-policy/opencode-permission`: the block for a role, as array maps in rule
   order (`"*": "deny"` and `external_directory: deny` first, allows after; edit and bash
   as rule objects with `"*": "allow"` first and the denies after), because OpenCode
   applies the last matching rule.
3. Both session writers put it in `:supervision` as `:opencode-permission`. The llm
   client sends it the way #2291 sends its deny block: as the permissions of an agent
   defined in the call's inline config, with a random name per process, selected with
   `--agent` and `--pure`. OpenCode applies an agent's rules after the global and
   project ones, so a repository's `opencode.json` cannot widen the role's tools.
   `cli-args/opencode-permission` picks the block (deny-all outside a session, the
   role's inside one).

## Testing Plan

1. `opencode-permission-gives-each-role-its-tools-test`: deny-first order for every
   role; the reviewer reads but cannot edit or run commands; the implementer's edit and
   bash rule objects deny agent config, `.git`, `git push` and `curl`; only the planner
   gets web access; an unlisted role gets nothing.
2. `opencode-call-outside-a-session-is-isolated-test`: a session call carrying the
   block runs `--pure` as the policy agent, the request's own agent dropped, with the
   block as that agent's permissions in rule order.
3. Checked with OpenCode 1.18.30 `debug config` / `debug agent build`: every key is
   accepted, rule objects keep their order, and the allowed tools are on and the rest off.

## Deployment Plan

Ships with the next release, after #2284 and #2291. OpenCode agents lose tools their
role does not list.

## Known limits

1. OpenCode matches bash rules as globs, so `:opencode/bash-deny` covers far less
   than the hook's regexes; the agent environment allowlist (#2284) and the capsule are
   the controls that hold.
2. OpenCode sessions still get no context MCP server, so they read through OpenCode's
   own tools, kept inside the worktree by `external_directory: deny`.

## Related Issues/PRs

1. #2284 (agent environment), #2291 (OpenCode outside sessions), #2251 (tool policy).

## Checklist

- [x] Tests fail on main and pass here
- [x] Checked against the real OpenCode config resolver
- [ ] CI green, Copilot comments resolved
