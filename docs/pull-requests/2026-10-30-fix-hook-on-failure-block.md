<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(agent): a session hook that times out blocks the call

## Overview

The PreToolUse hook handler in an artifact session's settings now sets
`"onFailure": "block"`. A hook that times out or fails to answer blocks the tool call
instead of falling back to the session's allow rules.

## Motivation

Claude Code treats a hook timeout as a non-blocking error: the call goes on to the
permission flow. In an artifact session (#2290) that flow is `dontAsk` with the role's
tools as allow rules, so a timed-out hook let any of the role's tools run without the
hook's path and command checks. Copilot's review of #2290 flagged this for a human
look; this closes it.

## Changes in Detail

1. `cli-config/pre-tool-use-settings`: the handler carries `"onFailure" "block"`, for
   host and capsule sessions.

## Testing Plan

1. `session-settings-allow-only-the-roles-tools-test`: the handler sets
   `onFailure: block` (fails on main).
2. Real Claude CLI 2.1.296 under `--setting-sources "" --permission-mode dontAsk` with a
   Bash allow rule and a hook that sleeps past a 2 s timeout: without `onFailure` the
   Bash call ran; with `"onFailure": "block"` it was blocked.
3. Agent hook tests pass.

## Deployment Plan

Ships with the next release.

## Known limits

1. `onFailure` needs Claude Code 2.1.295 or later. How an older CLI treats the key is
   not checked: if it ignored the key, timeouts would fall through as before; if it
   rejected the settings file, the session would have no allow rules and `dontAsk`
   would refuse every tool.

## Related Issues/PRs

1. #2290 (session settings), #2251 (default-deny hook).

## Checklist

- [x] Test fails on main and passes here
- [x] Real CLI check
- [ ] CI green, Copilot comments resolved
