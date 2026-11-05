<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(llm): agent CLI processes get an allowlisted environment

## Overview

An agent CLI process (claude, codex, cursor-agent, opencode), and the backend
preflight probe, now start with only the environment variables `:agent-env` in
`llm/client-defaults.edn` allows: the basics (PATH, HOME, locale, terminal, and their
Windows equivalents), proxies and TLS trust, provider keys and config, and the
variables the hook, the context server and their bb/Clojure tooling read. It replaces
`clean-env`, which passed miniforge's whole environment except `CLAUDECODE`.

## Motivation

The agent CLI hands its environment to every command its tools run. With the whole
environment passed through, an agent could read `GH_TOKEN`, `GITHUB_TOKEN`, AWS
credentials, `SSH_AUTH_SOCK` and miniforge's own tokens (`MINIFORGE_GIT_TOKEN`,
`MINIFORGE_FLEET_API_KEY`) with one Bash call, and use them. This keeps those values
out of the agent's environment. It does not cover what the operator's user can read
some other way on the host (see Known limits).

## Changes in Detail

1. `client-defaults.edn` `:agent-env`: `:allow` names, `:allow-prefixes` (`LC_`,
   `XDG_`, provider prefixes such as `ANTHROPIC_`, `CLAUDE_`, `OPENAI_`, `GEMINI_`,
   `OPENCODE_`, `AZURE_OPENAI_`, and the Clojure tooling's `CLJ_`, `DEPS_CLJ_`,
   `BABASHKA_`), and `:deny-prefixes` (`AWS_`, `GH_`, `GITHUB_`), which win over any
   allow entry. Names are compared upper-cased (Windows' `Path`).
2. `subprocess/allowed-env` filters an environment map by that policy;
   `subprocess/agent-env` applies it, plus the names an operator lists in
   `MINIFORGE_AGENT_ENV_ALLOW`, to `host-env` (miniforge's environment, a var tests
   redefine). `llm/agent-env` exposes it.
3. `start-capture-process!` (non-streaming) and `start-stream-process!` (streaming)
   always set the process environment to `agent-env`. The workflow runner's
   `run-cli-command` takes an `:env`; the backend preflight probes (version, health
   round trip) pass `llm/agent-env`, so a probe passes only where the run will.
   Host-side checks that run through it without `:env`, such as `mf doctor`'s
   `gh auth status`, keep miniforge's environment.

## Testing Plan

1. `agent-env-keeps-only-allowed-names-test`: from an operator environment holding
   GH/GitHub tokens, AWS credentials, `SSH_AUTH_SOCK`, miniforge tokens and
   `CLAUDECODE`, only the basics, proxy, provider keys and `MINIFORGE_HOME` remain.
2. `agent-cli-processes-see-only-allowed-env-test`: `env` run through
   `default-exec-fn` and `stream-exec-fn`, from that operator environment, prints none
   of the withheld names. (On main the child inherits the JVM's environment; the test
   needs `host-env`, added here, to supply one.)
3. `deny-prefix-wins-test`, `names-match-without-case-test`,
   `operator-can-add-names-test`; `probe-process-gets-the-agent-env-test` (CLI): with
   `:env` the process gets exactly that environment, without it the JVM's, and every
   backend probe passes `llm/agent-env`.
4. A real `claude` call through the llm client under the allowlist authenticates and
   answers.
5. llm component tests pass.

## Deployment Plan

Ships with the next release. An agent CLI that authenticates only through a variable
outside the allowlist now fails at preflight; list the name in
`MINIFORGE_AGENT_ENV_ALLOW` (comma-separated) or add it to `:agent-env :allow`.

## Known limits

1. Environment only. On the host the agent runs as the operator's user, so its tools
   can still read what that user can: credential files (`~/.aws/credentials`), keychain
   logins (`gh auth status` works from the keychain), `launchctl getenv`, and on Linux
   `/proc/<pid>/environ` of miniforge's own process. The hook's path checks and Bash
   patterns cover some of these as defense in depth; running the agent CLI inside the
   capsule is the control for the rest.
2. Claude on Bedrock through AWS credentials in the environment no longer works for
   agent CLI calls: `AWS_*` is withheld by design, and listing an `AWS_` name in
   `MINIFORGE_AGENT_ENV_ALLOW` does not override that. The direct HTTP backends run
   in the JVM and are unaffected.
3. Provider keys reach the agent by design: the CLI needs them, and its tools can
   read them. Capsule credentials are the path to keeping those out too.
4. The web dashboard's PR chat and summary run `claude -p` themselves with the full
   environment; a separate PR moves them onto the llm client.

## Related Issues/PRs

1. #2251, #2271: the tool hook this complements.

## Checklist

- [x] Tests fail on main and pass here
- [x] Independent review; findings fixed or listed above
- [x] Real CLI call under the allowlist
- [ ] CI green, Copilot comments resolved
