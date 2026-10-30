<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli): a config key that refuses local execution; pr respond honours the mode

## Overview

Adds `[:execution :require-governed?]` to the user config. When true, runs default to
governed (container capsule) execution and any run that would execute in another mode
is refused before it sets anything up. `mf pr respond` now takes `--execution-mode`
and passes it, with the PR branch, to the fix run.

## Motivation

Execution mode was only the `--execution-mode` flag, defaulting to `:local`: the agent
runs in a host worktree with the operator's privileges. An operator had no way to make
unisolated runs impossible, only to remember the flag on each run. `mf pr respond` had
no flag at all, so its fix runs were always local.

## Changes in Detail

1. `workflow-runner.execution-mode` (new): `resolve-mode` (explicit flag, else governed
   when required, else local; refuses a non-governed mode when required) and
   `allowed-mode!`, which reads the user config and throws an `:anomalies/forbidden`
   anomaly on refusal.
2. `run-workflow-from-spec!` resolves the mode first and uses it for the branch choice
   and the context. `run-workflow!` (named workflows, which always run locally) is
   refused when governed execution is required.
3. `mf pr respond --execution-mode <m>`: the fix run gets the mode and
   `:spec/branch` set to the checked-out PR branch, which a governed capsule clones
   (without it a capsule would clone main).
4. Config: the key in `default-user-config.edn` and its fallback (false), in the
   config schema as a boolean, and the refusal message.

## Testing Plan

1. `execution-mode-test`: the mode table; a spec run asked for `:local` and a named
   workflow run are refused under the key, before workflow setup; `pr respond` hands
   the fix run its mode and PR branch.
2. `config-schema-test` (every literal config path the code reads is in the schema),
   plus the runner provenance, error-message, dashboard-stream and JSON-output tests.

## Deployment Plan

Ships with the next release. The default (false) keeps today's behavior; operators
opt in with `mf config set execution.require-governed? true`.

## Related Issues/PRs

1. Not covered: other entry points that start workflows outside the CLI runner (the
   TUI and dashboard), and whether a governed `pr respond` fix run pushes from the
   capsule as the local run pushes from the host checkout. Both need a runtime check.

## Checklist

- [x] Tests fail on main and pass here
- [x] Config schema and both default files updated
- [ ] Independent review
- [ ] CI green, Copilot comments resolved
