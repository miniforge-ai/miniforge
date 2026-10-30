<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli): the web dashboard calls Claude through the llm client

## Overview

The web dashboard's PR chat (`chat-about-pr`) and PR summary (`generate-pr-summary`)
ran `claude -p <prompt>` themselves. They now ask the Claude backend through the llm
client (`ask-claude`), which runs a call outside an artifact session with no built-in
tools and none of the operator's Claude Code settings, hooks or plugins (#2282).

## Motivation

Both prompts carry text other people write: the PR title, body and diff, and for chat
a free-text question posted to the dashboard. `claude -p` with the operator's setup
could use whatever tools the operator's settings allow (on a machine in `auto`
permission mode, Bash), with no miniforge hook. A PR description that asks the model
to run a command could get it run on the operator's machine.

## Changes in Detail

1. `github/ask-claude`: one `llm/complete` on a `:claude` client; returns
   `{:success :text}`.
2. `chat-about-pr` and `generate-pr-summary` call it; their result shapes and error
   prefixes are unchanged.

## Testing Plan

1. `pr-chat-and-summary-run-claude-isolated-test`: both calls reach the Claude CLI with
   the four isolation flags and the prompt on stdin (fails on main, which runs
   `claude -p` directly); a failed call keeps its error prefix.
2. CLI web tests pass.
3. Independent adversarial review; findings fixed or listed below.

## Deployment Plan

Ships with the next release. The answers are text, as before. A call now times out
after 600 s (it had no limit); a blank error reads "Unknown".

## Known limits

1. As for every Claude call outside an artifact session (#2282), the operator's
   `~/.claude/settings.json` is not read: its `model`, `env` and `apiKeyHelper` do not
   apply. OAuth and environment variables do.
2. The llm client reports a successful answer as a rate-limit error when the answer
   itself mentions `429` or "rate limit" (`cli-output/rate-limited?` scans the whole
   answer). A summary of a PR about rate limiting can show as an error. That check is
   shared by every CLI backend; narrowing it is a separate change.

## Related Issues/PRs

1. #2282: isolation for Claude calls outside an artifact session.
2. #2284: the agent CLI environment allowlist, which these calls get once it merges.

## Checklist

- [x] Tests fail on main and pass here
- [ ] CI green, Copilot comments resolved
