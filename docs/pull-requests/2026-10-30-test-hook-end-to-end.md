<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# test(agent): a stub Claude CLI proves the session hook blocks a denied tool

## Overview

One end-to-end test drives a real artifact session with a stub Claude CLI. The stub
reads the session's `--settings` file, runs each PreToolUse hook command there the way
Claude does (`sh -c`, the request JSON on stdin; exit 2 or a deny decision blocks), and
writes the file only when every hook allows it. The hook it runs is miniforge's real
`hook-eval`, started by the command the session wrote.

## Motivation

The hook's pieces were tested apart: the policy, the reply shape, the settings file,
the Claude arguments. Nothing checked that they connect, so a session that wrote
settings Claude never runs, a hook command that cannot start, or a `--settings` flag
dropped from the arguments would each pass every unit test and leave tools unchecked.

## Changes in Detail

1. `hook-end-to-end-test/denied-tool-is-blocked-end-to-end-test`: a reviewer session's
   Write is refused and no file appears; an implementer session's Write is allowed and
   the file appears. A broken hook (any non-zero exit) counts as a refusal, so the
   allow case also proves the hook starts and answers.

## Testing Plan

1. The test passes; it runs `bb ... miniforge hook-eval` twice (about 2.5 s each).
2. The deny case requires the hook to exit 0 with `permissionDecision: deny`, so a
   crash cannot pass as a refusal. The stub runs only command hooks whose matcher
   selects Write, as Claude does.
3. It skips when `MINIFORGE_CMD` is set (the hook would run an installed miniforge).
4. PR CI runs it when the agent brick changes; a change only to the CLI's hook-eval or
   pr-lifecycle's guard reaches it on the main build.

## Deployment Plan

Test only.

## Related Issues/PRs

1. #2251 (default-deny hook), #2271 (path confinement).

## Checklist

- [x] The deny case fails if the session's settings carry no hook, or the hook allows
      the reviewer's Write
- [ ] CI green, Copilot comments resolved
