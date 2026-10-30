<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli,workflow): a config key that refuses local execution; pr respond honours it

## Overview

Adds `[:execution :require-governed?]` to the user config. When set, runs default to
governed (container capsule) execution, and a run that would execute locally is
refused: early in the CLI with a clear message, and again where the workflow runner
acquires every run's environment. `mf pr respond`, which cannot run governed yet,
refuses a governed request before it checks anything out.

## Motivation

Execution mode was only the `--execution-mode` flag, defaulting to `:local`: the agent
runs in a host worktree with the operator's privileges. An operator had no way to make
unisolated runs impossible, only to remember the flag on each run.

## Changes in Detail

1. `config.execution/governed-required?` (config interface): fails closed; any value
   other than false or nil (a hand-edited `"true"`, `"yes"`, `1`) counts as required.
   `user-config-requires-governed?` reads the user config file strictly: one that does
   not parse, or is not a map, counts as requiring governed execution (the merged-config
   loader would fall back to defaults and turn the requirement off).
2. `workflow-runner.execution-mode` (CLI): `resolve-mode` takes the explicit mode (a
   keyword or its name), else governed when required, else local; a value outside
   `#{:local :governed}` is unknown. `allowed-mode!` throws `:anomalies/incorrect` for an
   unknown mode and `:anomalies/forbidden` for a refused one.
3. `run-workflow-from-spec!` resolves the mode before workflow setup and uses it for the
   branch choice and the context; `run-workflow!` (named workflows, always local) is
   refused when governed execution is required.
4. Backstop: `runner-preparation/acquire-environment`, which every pipeline run passes,
   calls `refuse-local-when-governed-required!` before it acquires an environment or
   reuses a pre-acquired one. Chain steps, plan and DAG files, resumed runs, DAG child
   tasks and runs handed a pre-acquired executor (such as `--sandbox`) are covered. It
   runs before `acquire-execution-environment!`'s `try`, which would otherwise turn the
   refusal into a warning and a local run.
5. `mf pr respond --execution-mode <m>`: governed (requested or required by the config)
   and unknown modes exit 1 before `gh pr checkout`. The governed flow persists to a
   task branch and would not reach the PR branch, while the responder would still
   reply "fixed" and resolve the threads.
6. Config: the key in `default-user-config.edn` and its fallback (false), in the CLI
   config schema as a boolean, and the messages.

## Testing Plan

1. `config.execution-test`: the fail-closed table.
2. `execution-mode-test`: the mode table (keywords, names, unknown values); spec runs,
   named workflow runs and unknown modes are refused before workflow setup; `pr respond`
   exits 1 without checking out for a governed request or a governed-only config, and
   checks out for a local run.
3. `runner-environment-governed-required-test`: the backstop refuses no-mode and
   `:local` runs, fresh or pre-acquired, and lets governed ones through.
4. `config.execution-test`: the strict read of missing, setting, unparseable and
   non-map config files.
5. The runner-environment, governed-environment, config, config-schema and runner
   output tests (87 tests).

## Deployment Plan

Ships with the next release. The default (false) keeps today's behavior; operators opt
in with `mf config set execution.require-governed? true`.

## Known limits

1. DAG child tasks do not inherit the parent's execution mode (`dag-sub-workflow/task-sub-opts`),
   so under `--execution-mode governed` they run locally today. With the key set they
   are refused by the backstop instead, so a plan that fans out into tasks fails until
   children run governed. Follow-up.
2. `pr respond` refuses governed runs until the governed flow pushes the fix to the PR
   branch.
3. Tests that run a pipeline read the operator's user config; with the key set on a
   developer machine they are refused. CI has no user config.
4. Both CLI runners run their GC pass before the mode check.

## Related Issues/PRs

1. Default-deny tool hook (#2251) and the worker-containment follow-ups.

## Checklist

- [x] Tests fail on main and pass here
- [x] Config schema and both default files updated
- [x] Independent review; findings fixed or listed above
- [ ] CI green, Copilot comments resolved
