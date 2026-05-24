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

```bash
export GITHUB_TOKEN="ghp_..."
```

Without a GitHub token, the release phase will commit and push but skip
PR creation.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `GITHUB_TOKEN` | — | GitHub token for PR creation |
| `MINIFORGE_MAX_TOKENS` | `150000` | Max tokens per workflow |
| `MINIFORGE_MAX_ITERATIONS` | `50` | Max phase retries |
| `MINIFORGE_MAX_PARALLEL` | `4` | Max parallel DAG tasks |
| `MINIFORGE_MAX_COST_USD` | `100.0` | Cost budget per workflow |

## Workflow Tuning

Override defaults in your spec:

```clojure
{:spec/title "..."
 :spec/description "..."

 ;; Override workflow config
 :workflow/config
 {:max-tokens 50000
  :max-iterations 10
  :failure-strategy :retry}}
```

## Per-Phase Budgets

Individual phases can be tuned in the workflow definition:

```clojure
{:phase :implement
 :gates [:syntax :lint :no-secrets]
 :budget {:tokens 50000
          :iterations 5
          :time-seconds 300}}
```

## User Config File

Persistent configuration lives in `~/.miniforge/config.edn`:

```clojure
{:llm {:backend :opencode
       :model "anthropic/claude-sonnet-4-5"}
 :max-parallel 2
 :default-workflow :canonical-sdlc}
```

## Priority Order

Configuration is resolved in this order (highest priority first):

1. Workflow spec overrides (`:workflow/config`)
2. Environment variables (`MINIFORGE_*`)
3. User config file (`~/.miniforge/config.edn`)
4. Built-in defaults
