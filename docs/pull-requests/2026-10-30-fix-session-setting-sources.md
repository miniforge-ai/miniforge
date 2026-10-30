<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(llm,agent): artifact sessions load only miniforge's settings

## Overview

A Claude call inside an artifact session now runs with `--setting-sources ""
--permission-mode dontAsk`. None of the operator's user, project or local settings
load; the session's own `--settings` file still does, and it now carries the role's
tools from tool-policy.edn as `permissions.allow`. Claude refuses anything else instead
of asking. The hook's reply is unchanged (`{}` to allow, a deny decision to refuse), so
an allowed call still passes through Claude's own permission checks.

## Motivation

Agent sessions loaded the operator's whole Claude Code setup next to miniforge's hook.
On the machine this was built on that means `permissions.defaultMode: auto`, hooks on
ten events and ten plugins in every agent session. The hook's `{}` reply handed allowed
calls to that setup's permission flow, so what a role could run depended on the
operator's own settings, and their hooks, plugins and skills ran inside every session.
A repository's `.claude/settings.json` and `settings.local.json` loaded too, including
on branches miniforge checks out from other people's pull requests.

## Changes in Detail

1. `cli-args/claude-session-args`: `--setting-sources "" --permission-mode dontAsk`,
   added by `claude-args` to every call that has hook settings and is not isolated.
2. `cli-config/pre-tool-use-settings` takes the role's tools and writes them as
   `permissions.allow`; `role-allowed-tools` reads them from the shipped policy. Host
   and capsule sessions both pass them.
3. A session call no longer passes `--allowedTools`. The list there is shared by every
   role (it includes `context_write`), so it would have granted the reviewer a write if
   the hook ever let a call fall through; the settings file is now the only grant.

## Testing Plan

1. `claude-args-minimal-test`, `claude-args-isolated-test`: a supervised call gets the
   session flags and keeps its tools (fail on main).
2. `session-settings-allow-only-the-roles-tools-test`: a capsule reviewer's settings
   allow its context tools and no write or shell tool; a host implementer's allow Bash
   and the write tools; an unlisted role gets none.
3. Real Claude CLI 2.1.296 in a throwaway repository under these flags: with a
   PreToolUse hook answering `{}`, Bash ran only when an allow rule granted it, and a
   deny blocked it.
4. llm, agent hook and CLI hook tests pass.
5. Independent adversarial review: an explicit `permissionDecision: allow` from the hook
   (an earlier version of this change) skips Claude's own permission checks, so this
   keeps `{}` and grants through allow rules instead.

## Deployment Plan

Ships with the next release. Agent sessions no longer see the operator's Claude Code
settings, hooks, plugins or skills.

## Known limits

1. `--setting-sources ""` also stops Claude from loading the target repository's
   CLAUDE.md (checked: a CLAUDE.md instruction was followed with default sources and
   ignored under these flags; `--add-dir .` does not bring it back). Handing the
   repository's instructions to the agent explicitly is a follow-up.
2. A Bash allow rule grants any command the hook lets through; Claude's checks did not
   stop a write outside the worktree in the CLI check above. The Bash patterns, the
   agent environment allowlist (#2284) and the capsule remain the controls.
3. The planner's WebFetch is allowed for any URL, as tool-policy.edn grants it.
4. A hook that times out falls back to these allow rules: the role's own tools (and
   nothing else) run unchecked by the hook for that call.
5. As for isolated calls (#2282), `apiKeyHelper`, `env` and `model` from the
   operator's settings.json no longer apply to agent sessions.

## Related Issues/PRs

1. #2251 (default-deny hook), #2271 (path confinement), #2282 (isolation outside
   sessions), #2289 (end-to-end hook test).

## Checklist

- [x] Tests fail on main and pass here
- [x] Real CLI check
- [x] Independent review; findings fixed or listed above
- [ ] CI green, Copilot comments resolved
