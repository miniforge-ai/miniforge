<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(agent): default-deny tool policy for the PreToolUse hook

## Overview

The Claude PreToolUse hook (`mf hook-eval`) becomes default-deny: each agent role may
call only the tools `config/agent/tool-policy.edn` lists for it, and an error while
evaluating blocks the call.

## Motivation

On main the hook allowed any tool it did not name (a new Claude tool, an MCP tool
from another server, `Monitor` and `PowerShell`, which run commands without the Bash
check), allowed the read and write tools for every role, printed `{}` (allow) when it
could not read or evaluate a request, and let every call through when the hook could
not start (exit 127). Claude blocks a PreToolUse call only on exit code 2.

## Changes in Detail

1. `tool-policy.edn`: `:common` tools plus a set per role, matching the
   `--disallowedTools` lists the roles already pass to the CLI. The reviewer also loses
   `mcp__context__context_write`; WebSearch and WebFetch stay with the planner only.
2. `evaluate-tool-use` takes the role and denies an unlisted tool, every tool for a
   missing or unlisted role, and a listed Bash command matching a deny pattern.
3. `hook-eval-stdin!` writes nothing to stdout and exits 2 when evaluation throws.
4. The hook command is `<miniforge> hook-eval --role <role> || exit 2`, host and
   capsule; command words with characters outside a plain set are shell-quoted.
5. `create-base-agent` calls `invoke-fn` and `repair-fn` with `:agent/role` in the
   context; `with-session` and `with-readonly-session` read it.
6. The task-list tools (`TaskCreate`/`TaskGet`/`TaskList`/`TaskUpdate`) join `:common`.

## Testing Plan

1. Tool-policy, hook, reply and settings tests; each fails on main.
2. Real Claude Code CLI (2.1.296) in a throwaway repository: `--role reviewer` refused a
   Write, `--role implementer` allowed it, and a hook that cannot start refused it.
3. Independent adversarial review; findings fixed or listed below.

## Deployment Plan

Ships with the next release. Sessions opened with no agent role get a hook that
refuses every tool.

## Related Issues/PRs

1. #2248: removed the unreachable meta-eval path and moved the Bash patterns to EDN.
2. #2260: the artifact-session split, which this PR's settings changes now build on.
3. Follow-ups: worktree path checks for file tools; hook coverage for tool-less calls
   and `--setting-sources` on sessions; running the agent CLI inside the capsule.

## Known limits

1. In a capsule, `bb miniforge hook-eval` resolves from the capsule's working
   directory, which the agent can write. Moving the hook to a path the agent cannot
   write belongs with running the agent CLI inside the capsule.
2. In dev mode an older miniforge checkout above the working directory ignores
   `--role`.
3. The reviewer's enumeration retry runs with no artifact session, so no hook.
4. A hook that times out does not block the call.

## Checklist

- [x] Tests fail on main and pass here
- [x] Real CLI end-to-end check
- [x] Independent review
- [x] Rebuilt on the artifact-session split (#2260); the final diff passes stratum-lint with no opt-out
- [ ] CI green, Copilot comments resolved
