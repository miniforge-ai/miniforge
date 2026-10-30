<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(llm): agent CLI processes get an allowlisted environment

## Overview

An agent CLI process (claude, codex, cursor-agent, opencode) now starts with only the
environment variables `:agent-env` in `llm/client-defaults.edn` allows: the basics
(PATH, HOME, locale, terminal), proxies and TLS trust, provider keys and config, and
the few miniforge variables its hook and context server read. It replaces
`clean-env`, which passed miniforge's whole environment except `CLAUDECODE`.

## Motivation

The agent CLI hands its environment to every command its tools run. With the whole
environment passed through, an agent could read `GH_TOKEN`, `GITHUB_TOKEN`, AWS
credentials, `SSH_AUTH_SOCK` and miniforge's own tokens (`MINIFORGE_GIT_TOKEN`,
`MINIFORGE_FLEET_API_KEY`) with one Bash call, and use them. The hook's Bash deny
patterns are defense in depth only; keeping the values out of the process is the
control meant to hold.

## Changes in Detail

1. `client-defaults.edn` `:agent-env`: `:allow` names, `:allow-prefixes` (`LC_`,
   `XDG_`, and the provider prefixes `ANTHROPIC_`, `CLAUDE_`, `OPENAI_`,
   `OPENROUTER_`, `GEMINI_`, `OPENCODE_`), and `:deny-prefixes` (`AWS_`, `GH_`,
   `GITHUB_`), which win over any allow entry.
2. `subprocess/allowed-env` filters an environment map by that policy;
   `subprocess/agent-env` applies it to `host-env` (miniforge's environment, a var tests
   redefine).
3. `start-capture-process!` (non-streaming) and `start-stream-process!` (streaming)
   always set the process environment to `agent-env`.

## Testing Plan

1. `agent-env-keeps-only-allowed-names-test`: from an operator environment holding
   GH/GitHub tokens, AWS credentials, `SSH_AUTH_SOCK`, miniforge tokens and
   `CLAUDECODE`, only the basics, proxy, provider keys and `MINIFORGE_HOME` remain.
2. `agent-cli-processes-see-only-allowed-env-test`: `env` run through
   `default-exec-fn` and `stream-exec-fn` prints none of the withheld names (fails on
   main, where the child inherits everything).
3. `deny-prefix-wins-test`.
4. A real `claude` call through the llm client under the allowlist authenticates and
   answers.
5. llm component tests pass.

## Deployment Plan

Ships with the next release. An agent CLI that authenticates only through a variable
outside the allowlist stops authenticating; add the name to `:agent-env :allow`.

## Known limits

1. Claude on Bedrock through AWS credentials in the environment no longer works for
   agent CLI calls: `AWS_*` is withheld by design. The direct HTTP backends run in
   the JVM and are unaffected.
2. The backend preflight probe (`bases/cli` `process/run-cli-command`) still runs the
   CLI with miniforge's full environment.
3. Provider keys reach the agent by design: the CLI needs them, and its tools can
   read them. Capsule credentials are the path to keeping those out too.

## Related Issues/PRs

1. #2251, #2271: the tool hook this complements.

## Checklist

- [x] Tests fail on main and pass here
- [x] Real CLI call under the allowlist
- [ ] CI green, Copilot comments resolved
