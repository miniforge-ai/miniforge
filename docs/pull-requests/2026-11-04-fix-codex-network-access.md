<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Opt-in network access for the Codex agent in local runs

## Overview

Adds `[:llm :codex :network-access?]` to `config.edn`. It is a boolean,
off by default. When it is true, every `codex exec` call also gets
`-c sandbox_workspace_write.network_access=true`. The sandbox stays
`workspace-write` and `approval_policy` stays `never`. The setting applies
to local runs only: a governed run is refused while it is set. Fixes #2379.

## Motivation

`codex-args` fixes `--ignore-user-config`, `--sandbox=workspace-write` and
`-c approval_policy=never`. In that sandbox the commands the Codex agent
runs have no network, so it cannot push a branch or open a PR. There was no
supported way to change that for a run:

1. `create-client` and the CLI's `create-llm-client` pass only the backend
   and model; no extra-args or sandbox key exists.
2. `--ignore-user-config` skips `~/.codex/config.toml`.
3. Codex loads a project's `.codex/config.toml` but disables it unless the
   project is trusted (`codex-rs/config/src/loader/mod.rs`), which matches
   the issue's probe: the recorded policy stayed `network_access=false`.

The key name comes from the Codex source: `derive_permission_profile` in
`codex-rs/config/src/config_toml.rs` maps
`sandbox_workspace_write.network_access = true` to
`NetworkSandboxPolicy::Enabled` under `workspace-write`, and the TypeScript
SDK's `networkAccessEnabled` passes the same `--config` override. `-c`
overrides apply with `--ignore-user-config` (`codex-rs/exec/src/lib.rs`).
Read at openai/codex `c90354172c5c`; the codex CLI was not run.

## Changes in Detail

1. `cli_args.clj`: `codex-args` adds
   `-c sandbox_workspace_write.network_access=true` when the request's
   `:network-access?` is `true`. Without it the argv is unchanged.
2. `completion.clj`: `with-client-config` drops a request's own
   `:network-access?` and sets it from the client, so only the client's
   creator can turn it on.
3. `records/llm_client.clj`: `create-client` takes `:network-access?`. A
   non-boolean value, or `true` on a backend other than `:codex`, throws
   `:anomalies/incorrect` (message in the llm catalog).
4. `llm/interface.clj`: `client-network-access?` reads it off a client.
5. `config-schema.edn`: `[:llm :codex :network-access?]` is `:boolean`, so
   `mf config set` and `mf config validate` reject other values.
6. `workflow_runner/context.clj`: `create-llm-client` reads the key. A value
   other than true, false or nil throws `:invalid-input` naming the key. True
   on the codex backend creates the client with network access; other
   backends ignore the key. `refuse-network-access-when-governed!` throws
   `:anomalies/forbidden` for a governed run whose client has it.
7. `workflow_runner.clj`: a spec run checks both of its clients right after
   creating each one, before the workflow-selection and preflight calls.
8. `runner_environment.clj` / `runner_preparation.clj`: `acquire-environment`
   refuses a governed run whose client has network access, beside the
   `require-governed?` check. Every pipeline run passes there, so resumed
   runs, chain steps, plan runs and DAG child tasks are covered too.
9. `provenance.clj`: a run with the setting on prints
   `Codex network access: on` under the local-mode notice.
10. `docs/configuration.md`: new "Codex network access" section.

### Why local runs only

No policy pack rule mentions network. The capsule's launch plan can carry a
`:network-profile` (`:none`/`:restricted` become `--network=none`), but
the agent CLI does not run inside the capsule: the LLM client starts it as a
subprocess of the Miniforge process (`stream-exec`'s `ProcessBuilder`), and
the capsule exec-fn the runner builds (`:execution/exec-fn`) has no reader.
The capsule's network settings therefore do not bound the Codex process,
and allowing the setting in a governed run could give a governed run network
it would not otherwise have. Refusing is the fail-closed choice.

### Not covered

Miniforge's tool policy (the command deny list in
`agent/resources/config/agent/tool-policy.edn`, which denies `curl`, `ssh`
and `git push` to arbitrary remotes) reaches Claude Code through its
PreToolUse hook and OpenCode through its permissions. Miniforge applies
nothing like it to Codex, so with the setting on, any command the Codex
agent runs can reach any host. The docs say so. A per-host egress
allow-list for Codex is out of scope.

Not verified: whether Codex's own managed requirements
(`requirements.toml`) can override this `-c` setting. Miniforge does not
read or change them.

## Testing Plan

1. `llm/codex_network_access_test.clj`: the argv carries the override only
   for `true`, keeps `--sandbox=workspace-write` and `approval_policy=never`;
   a codex client created with the flag sends it on a real `complete` call;
   a request alone cannot turn it on; `create-client` rejects `"true"`, `1`,
   and `true` on `:claude` / `:opencode`.
2. `cli/workflow_runner/codex_network_access_test.clj`: config validation
   accepts booleans and reports `"yes"` as wrong-type; the key reaches a codex
   client and not a claude one; a non-boolean value stops the run; a
   governed spec run is refused before the workflow-selection call; the
   banner line appears only with the setting on.
3. `workflow/runner_environment_codex_network_test.clj`: `acquire-environment`
   refuses a governed run with network access, with or without a
   pre-acquired capsule, and lets governed runs without it and local runs
   with it through.

Against main's source the three files do not compile (they call
`client-network-access?` and `refuse-network-access-when-governed!`, which
main lacks). A probe of main's source without those vars shows the
behaviour they test is absent there: the argv has no override for
`:network-access? true`, a client created with it sends none,
`create-client` accepts `"yes"`, `config-problems` reports the key as
unknown, and `create-llm-client` accepts `"true"`. With this change the
three files pass, as do the existing `args-fn`, `context`, `provenance`,
`config-schema`, `execution-mode`, `runner-environment-governed-required`,
`build-initial-context` and `runner-preparation` tests.
`config-schema-source-scan-test` fails with the same 6 paths on main and
on this branch (`[:execution :status]`, `[:worktree-path]` and others from
files this PR does not touch).

## Deployment Plan

No migration. The setting is off by default, so existing runs send the
same argv as before. An operator turns it on with
`mf config set llm.codex.network-access? true`.

## Related Issues/PRs

1. Fixes #2379.
2. Not touched: `cli_output.clj` and the workflow summary display, which
   #2378's change owns.

## Checklist

- [x] Default argv unchanged; tests for the new flag fail on main
- [x] Config value validated as a boolean at `config set`/`validate` and at run start
- [x] Governed runs refused, early in the CLI and again at environment acquisition
- [x] User-facing strings in the message catalogs
- [x] Stratum metadata on new functions and tests
- [ ] CI and review
