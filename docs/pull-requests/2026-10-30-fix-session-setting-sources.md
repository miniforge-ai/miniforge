<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(llm,agent): artifact sessions load only miniforge's settings; the hook allows explicitly

## Overview

A Claude call inside an artifact session now runs with `--setting-sources ""
--permission-mode dontAsk`: none of the operator's user, project or local settings
load (the session's own `--settings` file still does), and Claude refuses any tool
the hook does not explicitly allow. `hook-eval` now answers an allowed call with
`permissionDecision: "allow"` instead of `{}`. The tool policy then decides alone.

## Motivation

Agent sessions loaded the operator's Claude Code setup alongside miniforge's hook. On
the machine this was built on that means `permissions.defaultMode: auto`, hooks on ten
events and ten plugins in every agent session. The hook's `{}` reply handed allowed
calls to that setup's permission flow, so whether a role could run Bash depended on
the operator's own settings, and their hooks, plugins and skills ran inside every
session. A repository's `.claude/settings.json` and `settings.local.json` loaded too.

## Changes in Detail

1. `cli-args/claude-session-args`: `--setting-sources "" --permission-mode dontAsk`,
   added by `claude-args` to every call that has hook settings and is not isolated.
2. `tool-supervisor/hook-reply`: allow is
   `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"}}`.

## Testing Plan

1. `claude-args-minimal-test` and `claude-args-isolated-test`: a supervised call gets
   the session flags and keeps its tools (fail on main).
2. `allow-is-an-explicit-decision-test`, `hook-eval-stdin-reads-one-request-and-responds-test`,
   CLI `hook-eval-test`: allowed calls get `permissionDecision: "allow"`.
3. Real Claude CLI 2.1.296 in a throwaway repository under
   `--setting-sources "" --permission-mode dontAsk` with a PreToolUse hook: a `{}` reply
   left Bash refused, an explicit allow ran it, a deny blocked it.
4. llm, agent hook and CLI hook tests pass.

## Deployment Plan

Ships with the next release. Agent sessions no longer see the operator's Claude Code
settings, hooks, plugins or skills; a role gets exactly the tools tool-policy.edn
lists for it.

## Known limits

1. A hook that times out falls back to Claude's own permission flow. Under `dontAsk`
   that refuses Bash and anything not pre-approved, but the tools in the session's
   `--allowedTools` (the context MCP tools, Write, Edit, MultiEdit) still run.
2. As for isolated calls (#2282), `apiKeyHelper`, `env` and `model` from the
   operator's settings.json no longer apply to agent sessions.

## Related Issues/PRs

1. #2251 (default-deny hook), #2271 (path confinement), #2282 (isolation outside
   sessions).

## Checklist

- [x] Tests fail on main and pass here
- [x] Real CLI check
- [ ] CI green, Copilot comments resolved
