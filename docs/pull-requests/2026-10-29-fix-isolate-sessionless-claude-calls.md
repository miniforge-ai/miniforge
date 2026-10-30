<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(llm): isolate Claude calls that run outside an artifact session

## Overview

A Claude CLI call with no PreToolUse hook settings now runs isolated: no built-in
tools, none of the operator's settings, hooks, plugins or skills, and no host MCP
servers. `create-client` also takes `:isolated?` (and a request may set it) to isolate
calls that do carry hook settings. This carries #2081 onto the split llm-client
namespaces and adds the sessionless default.

## Motivation

Only an artifact session writes the hook settings (`--settings`) that run
`mf hook-eval`. Every other Claude call (the curator, the reviewer's enumeration
retry, scan, the workflow recommender, operator and calibration calls, TUI calls)
ran with no hook and loaded the operator's full Claude Code setup. That setup decided
which tools the call could use: with `permissions.defaultMode: auto`, a call meant
as text-only could run Bash or write files with no miniforge check. It also cost
tokens and answers: a plugin's SessionStart hook told the model to load a skill, so
about one call in six ended on a tool call with no answer, and every call carried
~36K tokens of setup context (#2081).

## Changes in Detail

1. `cli-args/claude-isolation-args`: `--tools "" --setting-sources ""
   --disable-slash-commands`, plus `--strict-mcp-config` when no `--mcp-config` is
   given.
2. `cli-args/claude-args` adds them when the request sets `:isolated?` or has no
   `:settings-path` under `:supervision`.
3. `completion/with-client-config`: the client's model, Ollama context size and
   isolation reach each request through one helper, used by `complete-impl`,
   `handle-streaming` and the HTTP branch of `complete-stream-impl`.
4. `create-client` (records and interface) accepts `:isolated?`.

## Testing Plan

1. `claude-args-isolated-test`: a request with no hook settings gets the flags (fails
   on main); `:isolated?` adds them to a supervised request; one
   `--strict-mcp-config` with an `--mcp-config`; a supervised request gets none.
2. `sessionless-claude-call-is-isolated-test`: a plain client sends the flags on
   `complete` and `complete-stream` without hook settings (fails on main), and not
   with them.
3. `isolated-client-sends-isolation-flags-test`, `with-client-config-test` (#2081).
4. `bb test` for the llm component: 236 tests, 0 failures.

## Deployment Plan

Ships with the next release. Text-only Claude calls lose tool access they were never
meant to have; no caller outside an artifact session asks for tools.

## Related Issues/PRs

1. Supersedes #2081 (same change on the pre-split file).
2. #2279: the llm-client split this builds on.
3. Follow-ups: explicit `--setting-sources` and permission mode on artifact sessions;
   a tool-free mode for the Codex and Cursor backends, which still run every call with
   write access.

## Checklist

- [x] Tests fail on main and pass here
- [x] llm component tests pass
- [ ] CI green, Copilot comments resolved
