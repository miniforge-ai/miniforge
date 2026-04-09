# Configuration

## LLM Backend

Miniforge auto-detects your LLM backend in this priority order:

1. **Claude Code CLI** — if `claude` is on your PATH
2. **Codex CLI** — if `codex` is on your PATH
3. **Anthropic API** — if `ANTHROPIC_API_KEY` is set
4. **OpenAI API** — if `OPENAI_API_KEY` is set

No configuration is needed if you have Claude Code or Codex installed.

### API Key Setup

```bash
# Anthropic (Claude)
export ANTHROPIC_API_KEY="sk-ant-..."

# OpenAI
export OPENAI_API_KEY="sk-..."
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
| `ANTHROPIC_API_KEY` | — | Anthropic API key |
| `OPENAI_API_KEY` | — | OpenAI API key |
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
{:llm-backend :anthropic
 :max-parallel 2
 :default-workflow :canonical-sdlc}
```

## Priority Order

Configuration is resolved in this order (highest priority first):

1. Workflow spec overrides (`:workflow/config`)
2. Environment variables (`MINIFORGE_*`)
3. User config file (`~/.miniforge/config.edn`)
4. Built-in defaults
