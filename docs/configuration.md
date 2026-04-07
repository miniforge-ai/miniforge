# Configuration Management

> This document covers configuration for the **Miniforge** SDLC product (CLI/TUI). MiniForge Core exposes configuration
seams that products customize via their own `app.edn`; the settings below are Miniforge-specific defaults and overrides.

Miniforge uses [Aero](https://github.com/juxt/aero) for centralized configuration with environment variable overrides.

## Configuration File

The main configuration file is located at `bases/cli/resources/config.edn`.

## Configuration Structure

```clojure
{:llm
 {:backend #or [#env MINIFORGE_LLM_BACKEND :claude]
  :timeout-ms #or [#env MINIFORGE_LLM_TIMEOUT 300000]
  :line-timeout-ms #or [#env MINIFORGE_LLM_LINE_TIMEOUT 60000]
  :max-tokens #or [#env MINIFORGE_LLM_MAX_TOKENS 4000]}

 :workflow
 {:max-iterations #or [#env MINIFORGE_MAX_ITERATIONS 50]
  :max-tokens #or [#env MINIFORGE_MAX_TOKENS 150000]
  :failure-strategy #or [#env MINIFORGE_FAILURE_STRATEGY :retry]}

 :artifacts
 {:dir #or [#env MINIFORGE_ARTIFACTS_DIR "~/.miniforge/artifacts"]}

 :meta-loop
 {:enabled #or [#env MINIFORGE_META_LOOP_ENABLED true]
  :max-convergence-iterations #or [#env MINIFORGE_META_MAX_ITERATIONS 10]
  :convergence-threshold #or [#env MINIFORGE_META_THRESHOLD 0.95]}}
```

## Environment Variable Overrides

All configuration values can be overridden with environment variables using Aero's `#env` tag syntax:

### LLM Configuration

- `MINIFORGE_LLM_BACKEND` - LLM backend to use (default: `:claude`)
  - Values: `:claude`, `:openai`, `:anthropic`, etc.
- `MINIFORGE_LLM_TIMEOUT` - Total timeout for LLM calls in milliseconds (default: `300000` = 5 minutes)
- `MINIFORGE_LLM_LINE_TIMEOUT` - Per-line timeout for streaming in milliseconds (default: `60000` = 1 minute)
- `MINIFORGE_LLM_MAX_TOKENS` - Maximum tokens per LLM request (default: `4000`)

### Workflow Configuration

- `MINIFORGE_MAX_ITERATIONS` - Maximum workflow iterations (default: `50`)
- `MINIFORGE_MAX_TOKENS` - Maximum total tokens for workflow (default: `150000`)
- `MINIFORGE_FAILURE_STRATEGY` - How to handle failures (default: `:retry`)
  - Values: `:retry`, `:fail-fast`, `:continue`

### Artifact Configuration

- `MINIFORGE_ARTIFACTS_DIR` - Directory for workflow artifacts (default: `~/.miniforge/artifacts`)

### Meta-Loop Configuration

- `MINIFORGE_META_LOOP_ENABLED` - Enable meta-loop self-improvement (default: `true`)
- `MINIFORGE_META_MAX_ITERATIONS` - Maximum iterations for convergence (default: `10`)
- `MINIFORGE_META_THRESHOLD` - Convergence threshold (default: `0.95`)

## Usage Examples

### Using Default Configuration

```bash
# Run workflow with default config
mf run examples/workflows/implement-feature.edn
```

### Override LLM Backend

```bash
# Use OpenAI instead of Claude
MINIFORGE_LLM_BACKEND=openai mf run examples/workflows/implement-feature.edn
```

### Increase Timeout for Long Operations

```bash
# Set 10-minute timeout for complex workflows
MINIFORGE_LLM_TIMEOUT=600000 mf run examples/workflows/complex-refactor.edn
```

### Meta-Loop Convergence Tuning

```bash
# Run meta-loop with more iterations and higher threshold
MINIFORGE_META_MAX_ITERATIONS=20 \
MINIFORGE_META_THRESHOLD=0.98 \
mf run examples/workflows/meta-loop-self-improvement.edn
```

### Multiple Overrides

```bash
# Combine multiple environment overrides
MINIFORGE_LLM_BACKEND=anthropic \
MINIFORGE_MAX_ITERATIONS=100 \
MINIFORGE_ARTIFACTS_DIR=/tmp/artifacts \
mf run examples/workflows/implement-feature.edn
```

## Priority Order

Configuration values are determined in this priority order:

1. **Workflow override** - Specified in workflow spec (`:workflow/config` or `:spec/raw-data`)
2. **Environment variable** - Set via shell environment
3. **Config file** - Default value in `config.edn`
4. **Hardcoded default** - Fallback in code (e.g., `:claude` for backend)

Example:

```clojure
;; In workflow spec file
{:workflow/config {:llm-backend :openai}  ; Highest priority
 ...}
```

## Programmatic Access

Configuration is loaded automatically by the workflow runner. To access config in custom code:

```clojure
(require '[ai.miniforge.cli.config :as config])

;; Load configuration
(def cfg (config/load-config))

;; Get specific values with defaults
(config/get-llm-backend cfg nil)        ; Get backend with no override
(config/get-llm-timeout cfg)            ; Get timeout
(config/get-llm-line-timeout cfg)       ; Get line timeout

;; With workflow override
(config/get-llm-backend cfg :openai)    ; Override with :openai
```

## Adding New Configuration

To add new configuration values:

1. Add to `bases/cli/resources/config.edn`:

   ```clojure
   {:my-section
    {:my-value #or [#env MY_ENV_VAR "default"]}}
   ```

2. Add helper function in `bases/cli/src/ai/miniforge/cli/config.clj`:

   ```clojure
   (defn get-my-value
     "Get my-value from config."
     [config]
     (get-in config [:my-section :my-value] "default"))
   ```

3. Use in code:

   ```clojure
   (def cfg (config/load-config))
   (def my-val (config/get-my-value cfg))
   ```

## Benefits

- **Centralized**: All configuration in one place
- **Environment-aware**: Easy override for dev/test/prod
- **Meta-loop compatible**: Agent can tune settings via env vars during convergence
- **Type-safe**: Aero validates configuration structure
- **Babashka compatible**: Works in both JVM and Babashka runtimes

## See Also

- [Aero Documentation](https://github.com/juxt/aero)
- [Workflow Configuration](./workflows.md)
- [Meta-Loop Architecture](./meta-loop.md)
