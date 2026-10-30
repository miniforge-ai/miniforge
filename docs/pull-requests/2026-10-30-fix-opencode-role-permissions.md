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
   `--agent` and `--pure`, with the worktree's project config off (as #2291 does).
   `cli-args/opencode-permission` picks the block: the role's for a session call that
   carries one, deny-all for anything else, including a session call that carries none.
4. Every OpenCode call now runs this way, so `opencode-args` drops the request's own
   `:agent` and refuses `:attach`, `:command`, `:session`, `:continue?` and `:fork?` for
   every call (no caller passes them).
5. The llm client sets `external_directory` to a rule object that denies `*` and OpenCode's
   own tool-output glob (`<data>/opencode/tool-output/*`) by name: OpenCode's agent
   resolver appends an allow for that directory, which holds every session's full tool
   output, unless that exact glob is denied.
6. `:opencode/edit-deny` covers the `.git` pointer file of a linked worktree and nested
   `.git` directories (`.git`, `*/.git`, `*/.git/*`, and `.GIT`/`.Git` spellings).

## Testing Plan

1. `opencode-permission-gives-each-role-its-tools-test`: deny-first order for every
   role; the reviewer reads but cannot edit or run commands; the implementer's edit and
   bash rule objects deny agent config, `.git`, `git push` and `curl`; only the planner
   gets web access; an unlisted role gets nothing.
2. `opencode-env-sets-the-calls-permissions-test`: deny-all outside a session, for an
   isolated call and for a session call without a block; a session call's block reaches
   the agent in rule order at real sizes (a 9-key block with a 27-key bash object);
   project config and plugins off; the operator's inline config kept.
   `opencode-args-refuses-server-command-and-resume-test`: refused in and out of
   sessions. The tool-policy test checks the implementer's serialized block keeps its
   order and denies `.git` paths.
3. Checked with OpenCode 1.18.30 `debug config` / `debug agent build`: every key is
   accepted, rule objects keep their order, and the allowed tools are on and the rest off.
   With `external_directory: "deny"` the resolver still appended an allow for
   `~/.local/share/opencode/tool-output/*` after it; with the exact glob denied, no
   external_directory allow came after the final deny.

## Deployment Plan

Ships with the next release, after #2284 and #2291. OpenCode agents lose tools their
role does not list.

## Known limits

1. OpenCode matches bash rules as globs, so `:opencode/bash-deny` covers far less
   than the hook's regexes; the agent environment allowlist (#2284) and the capsule are
   the controls that hold.
2. OpenCode matches edit rules case-sensitively except on Windows, so on a
   case-insensitive macOS volume other spellings of the listed paths get through.
3. The role block's `read: allow` overrides OpenCode's default ask on `*.env` files,
   as the Claude hook allows those reads.
4. OpenCode sessions still get no context MCP server, so they read through OpenCode's
   own tools, kept inside the worktree by `external_directory: deny`. OpenCode checks
   that path lexically, so a symlink in the worktree (one committed on a branch under
   review, say) that points at a file outside it can be read, and a link can alias a
   protected edit path. The Claude hook resolves paths before it checks them; OpenCode
   runs no hook, so the capsule's filesystem boundary is the control for this.
5. Every OpenCode call now carries env entries, so the capsule exec adapter refuses it
   (#2291). No governed LLM client installs that adapter today (`runner-preparation`
   only stores it in `:execution/exec-fn`, which nothing reads); carrying the config
   into a capsule is part of running the agent CLI there.

## Related Issues/PRs

1. #2284 (agent environment), #2291 (OpenCode outside sessions), #2251 (tool policy).

## Checklist

- [x] Tests fail on main and pass here
- [x] Checked against the real OpenCode config resolver
- [ ] CI green, Copilot comments resolved
