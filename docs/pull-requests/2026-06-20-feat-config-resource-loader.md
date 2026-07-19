<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# feat: shared config-resource loader in ai.miniforge.config

## Overview

Adds one home for the "load a component's EDN config resource from the
classpath" pattern, so components stop hand-rolling their own
`(-> (io/resource ...) slurp edn/read-string)` loaders. New namespace
`ai.miniforge.config.resource`, two functions exported from the config
interface.

## Motivation

The config-as-data work spread a near-identical private `load-config`
helper across many components. That is the duplication this consolidates.
The config component already owns config loading (user / repo / merged /
governance) and has light deps (clojure, fs, aero), so it is the natural
owner; depending on it introduces no cycles.

## API

- `load-config-resource` — `[path]` / `[path required-keys]`. Fails fast
  with a clear `ex-info` (carrying `:config/resource`, and
  `:config/missing-keys` for the key check) when the resource is absent
  from the classpath, malformed, not a map, or missing a required key.
  Returns the parsed map. Use where the values are required.
- `read-config-resource-or` — `[path fallback]`. Fail-open: returns
  `fallback` on any error (absent / unreadable / malformed / non-map). Use
  where call sites already carry literal defaults or the component is
  documented to fail open.

## Follow-up

Subsequent PRs route the existing inline loaders (self-healing,
orchestrator, operator, task-executor, tool-registry, adapter-claude-code,
web-dashboard, phase-deployment, semantic-analyzer, agent, llm) through
these functions and add the `ai.miniforge/config` dependency where it is
not already present.

## Verification

- `ai.miniforge.config.resource-test`: happy path, missing resource throws
  with `:config/resource`, missing required key throws with
  `:config/missing-keys`, fail-open returns the fallback. Config component
  suite: 32 tests, 130 assertions, 0 failures.
- `bb poly:check`: OK.
