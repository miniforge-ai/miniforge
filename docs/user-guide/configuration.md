<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Configuration

## LLM Backend

Miniforge uses OpenCode as the default provider/auth wrapper for LLM
access. Configure provider keys in OpenCode, then let miniforge invoke
`opencode run`.

Claude Code and Codex remain supported direct CLI backends for users who
already have those tools configured, but miniforge no longer accepts
provider API keys directly.

### Provider Key Setup

```bash
opencode auth login
opencode models
```

### GitHub Token (for PR creation)

The release phase opens PRs with the `gh` CLI. It uses the first token it
finds in `MINIFORGE_GIT_TOKEN`, `GH_TOKEN`, or `gh auth token`, so
`gh auth login` is enough on a workstation.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `MINIFORGE_HOME` | `~/.miniforge` | Directory for the user config file and artifacts |
| `MINIFORGE_LLM_BACKEND` | `opencode` | LLM backend; `mf config backends` lists them |
| `MINIFORGE_LLM_MODEL` | not set | Model for the execution roles, and for the planner where miniforge has no default |
| `MINIFORGE_AGENT_THINKING_MODEL` | not set | Model for the planner and architect |
| `MINIFORGE_AGENT_EXECUTION_MODEL` | not set | Model for every other role |
| `MINIFORGE_GIT_TOKEN`, `GH_TOKEN` | — | Token for PR creation |

With no model set, each role uses miniforge's default for the backend, or
the backend's own; [Models](../configuration.md#models) gives the order.
[Configuration Management](../configuration.md) lists the rest.

## Timeouts and Budgets

No environment variable or user-config key sets token budgets, cost
budgets, iteration limits, parallelism or LLM timeouts.
`MINIFORGE_MAX_TOKENS`, `MINIFORGE_MAX_ITERATIONS`, `MINIFORGE_MAX_PARALLEL`
and `MINIFORGE_MAX_COST_USD` appeared here before, but nothing read them.

Phase retries are set per phase in the workflow definition. Only
`:iterations` is enforced; the phase schema also accepts `:tokens` and
`:time-seconds`, but no phase reads them.

```clojure
{:phase :implement
 :gates [:syntax :lint :no-secrets]
 :budget {:iterations 5}}
```

## User Config File

Persistent configuration lives in `~/.miniforge/config.edn`. Create it
with `mf config init`, view it with `mf config list`.

```clojure
{:llm {:backend :opencode}
 ;; Optional: models per role tier. Without them each role uses its
 ;; default for the backend.
 :agents {:default-models {:thinking  "anthropic/claude-opus-4-6"
                           :execution "anthropic/claude-sonnet-4-6"}}}
```

## Priority Order

Configuration is resolved in this order (highest priority first):

1. `:spec/llm-backend` in the spec (backend only)
2. Environment variables (`MINIFORGE_*`)
3. Repository config (`.miniforge/config.edn`, `:policy-packs` only)
4. User config file (`~/.miniforge/config.edn`)
5. Built-in defaults
