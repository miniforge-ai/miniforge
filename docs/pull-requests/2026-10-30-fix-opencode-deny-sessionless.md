<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(llm): OpenCode calls outside an artifact session get every permission denied

## Overview

OpenCode (miniforge's default backend) allows most tools when its config says
nothing, and miniforge passed it no permission config. An OpenCode call outside an
artifact session, which has no miniforge hook, now runs with `--pure` (no external
plugins) and `OPENCODE_CONFIG_CONTENT` set to a permission block that denies every
tool. OpenCode applies that inline config over its global and project config.

## Motivation

The OpenCode counterpart of #2282 (Claude). A text-only call (the curator, the
reviewer's enumeration retry, scan, the workflow recommender) could edit files or run
Bash under OpenCode's defaults with no check.

## Changes in Detail

1. `client-defaults.edn` `[:opencode :deny-all-permission]`: every OpenCode
   permission key set to `deny`, plus `"*"`.
2. `cli-args/opencode-env`: OPENCODE_CONFIG_CONTENT with that block for a call with no
   hook settings, or one that asks to be `:isolated?`; nil for a session call.
   `opencode-args` adds `--pure` in the same cases.
3. A backend entry may carry an `:env-fn` (OpenCode does). `complete-impl` passes its
   entries as `:env` exec options; `default-exec-fn` and `start-capture-process!` set
   them on top of the agent environment allowlist.
4. The backend preflight probe passes the same entries, so an OpenCode probe runs as a
   real call outside a session would.

## Testing Plan

1. `opencode-call-outside-a-session-is-isolated-test`: `--pure` and an all-deny
   permission block with no hook settings or with `:isolated?`; neither for a session
   call (fails on main).
2. `opencode-call-carries-its-deny-config-test`: an OpenCode client's exec gets the
   config; `backend-env-entries-reach-the-process-test`: the entries reach the child
   process on top of the allowlist.
3. The OpenCode preflight test now expects `--pure` and the config.
4. Checked with OpenCode 1.18.30's `opencode debug config` and `debug agent build`: the
   inline block overrides a project `opencode.json` that allows bash and edit, and its
   deny rules come after the build agent's own allow rules, so every tool resolves to
   off. No model call: the local OpenCode provider credentials did not work.

## Deployment Plan

Ships with the next release, after #2284 (this builds on its process environment).
OpenCode calls inside an artifact session are unchanged here; giving them each role's
tool permissions is the next change.

## Known limits

1. `--attach` sends the call to a running OpenCode server, which uses its own config.
2. A capsule call (`capsule-exec-fn`) does not carry the env entries yet.

## Related Issues/PRs

1. #2282 (Claude isolation), #2284 (agent environment allowlist).

## Checklist

- [x] Tests fail on main and pass here
- [ ] CI green, Copilot comments resolved
