<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor(cli): externalize default user config to EDN

## Overview

Moves the fallback user config in `bases/cli/src/ai/miniforge/cli/config.clj`
out of an in-code literal map and into an EDN resource at
`bases/cli/resources/config/cli/user-defaults.edn`. The `default-config` var
keeps its name and now binds to the loaded resource.

## Motivation

`default-config` held operational settings (LLM model name, timeouts, token
limits, dashboard port, convergence thresholds) as a literal map embedded in
code. This is config-as-data (Dewey 007): such values belong in EDN resources
alongside the existing `app.edn` and `backends.edn`, which the same base
already loads via `resource-config/merged-resource-config`.

## Changes

- Added `bases/cli/resources/config/cli/user-defaults.edn` carrying the static
  defaults: `:llm`, `:workflow`, `:meta-loop`, and `:dashboard`. Keys and
  values are copied verbatim from the previous in-code map. The file uses the
  Apache-2.0 header block.
- `config.clj` now requires `ai.miniforge.cli.resource-config` and defines
  `default-config-resource` plus a rewritten `default-config` that loads the
  EDN through `resource-config/merged-resource-config` (the same loader used
  for `app.edn` and `backends.edn`).
- The `:artifacts {:dir ...}` entry stays computed in code. Its value comes
  from `app-config/artifacts-dir`, which resolves against the runtime home dir
  (honoring `MINIFORGE_HOME`), so it cannot be frozen into a static literal.
  `default-config` assocs it onto the loaded EDN, preserving the prior value.

No numbers, keywords, or strings were changed. Map equality is order
independent, so the relocation of `:artifacts` to the end of the map does not
alter `default-config`.

## Verification

- Load smoke check via the `:dev` classpath: `default-config` equals the
  original literal map (`(= default-config {...original...})` returns `true`).
  Spot values: `[:llm :model]` = `"anthropic/claude-sonnet-4-5"`,
  `[:dashboard :port]` = `7878`, `[:meta-loop :convergence-threshold]` = `0.95`,
  `[:artifacts :dir]` resolves at runtime.
- CLI base tests: 287 tests, 1056 assertions. The one failure
  (`workflow-runner.preflight-test`) is pre-existing on `origin/main` and
  environmental (it shells out to the local `claude` CLI binary); it does not
  reference config defaults and is unrelated to this change.
- `bb poly:check`: `OK`, exit 0.
