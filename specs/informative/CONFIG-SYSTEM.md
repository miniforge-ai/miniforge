# Frictionless Configuration System

**Status**: ✅ Implemented (All 5 Phases Complete)

## Overview

Miniforge now has a comprehensive, user-friendly configuration system that makes it simple to discover, configure, and switch between LLM backends. Configuration is no longer a "rite of passage" - it's discoverable, helpful, and frictionless.

## Features Implemented

### Phase 1: Fixed Config Commands ✅

- Fixed babashka/cli dispatch error with `--help` flag
- `miniforge config --help` now works correctly
- All config subcommands properly registered

### Phase 2: User Config File ✅

- User config at `~/.miniforge/config.edn`
- Auto-created on first use
- Configuration precedence: user config > env vars > defaults
- Implemented in `components/config/src/ai/miniforge/config/user.clj`

### Phase 3: Multiple Backend Support ✅

- **6 backends supported:**
  - `:claude` - Anthropic Claude via CLI (streaming)
  - `:codex` - OpenAI Codex via API (streaming)
  - `:openai` - OpenAI GPT-4 via API (streaming)
  - `:ollama` - Local models via Ollama (streaming)
  - `:cursor` - Cursor AI via CLI
  - `:echo` - Testing backend (built-in)
- HTTP API clients for OpenAI and Ollama backends
- Automatic API key validation
- Implemented in `components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj`

### Phase 4: Backend Discovery ✅

- Backend status checking (available, needs-key, not-installed)
- Backend information display with helpful installation instructions
- Current backend indicator
- Implemented in `bases/cli/src/ai/miniforge/cli/backends.clj`

### Phase 5: Improved Config CLI ✅

- **9 config commands:**
  - `list` - Show all configuration
  - `get <key>` - Get specific value
  - `set <key> <value>` - Set value
  - `backends` - List available backends with status
  - `backend <name>` - Set backend (shorthand)
  - `init` - Create user config file
  - `edit` - Open config in $EDITOR
  - `reset` - Reset to defaults
  - `validate` - Validate config file
- Helpful output with examples
- Color-coded status indicators
- Implemented in `bases/cli/src/ai/miniforge/cli/config.clj`

## User Experience Examples

### List Available Backends

```bash
$ miniforge config backends

Available LLM Backends:

✅ claude (current) (Anthropic)
   Claude via Anthropic CLI
   Status: claude CLI found
   Models: claude-sonnet-4-20250514, claude-opus-4-20250514, claude-haiku-4-20250514

⚠️  codex (OpenAI)
   OpenAI Codex via API
   Status: Needs OPENAI_API_KEY
   Models: gpt-4-turbo, gpt-4, gpt-3.5-turbo
   Install: Set OPENAI_API_KEY environment variable
   Docs: https://platform.openai.com/api-keys

❌ cursor (Cursor)
   Cursor AI via CLI
   Status: cursor-cli not found on PATH
   Install: Install from https://cursor.sh
```

### Set Backend (with helpful error)

```bash
$ miniforge config backend codex

❌ Error: codex backend is not available

The codex backend requires OPENAI_API_KEY

To use this backend:
  1. Get an API key from https://platform.openai.com/api-keys
  2. Set the environment variable:
     export OPENAI_API_KEY='your-key-here'
  3. Or add to ~/.miniforge/config.edn:
     {:llm {:backend :codex}}
  4. Try again: miniforge config backend codex
```

### Set Backend (successful)

```bash
$ export OPENAI_API_KEY='sk-...'
$ miniforge config backend codex

✅ Backend set to codex
   Saved to: ~/.miniforge/config.edn
   Provider: OpenAI
   Model: gpt-4-turbo (default)

To change model:
  miniforge config set llm.model gpt-4
```

### View Configuration

```bash
$ miniforge config list

Current Configuration:

LLM Settings:
  backend: codex (OpenAI)
  model: gpt-4-turbo
  max-tokens: 4000
  timeout: 5 minutes

Workflow Settings:
  max-iterations: 50
  max-tokens: 150000
  failure-strategy: retry

Artifacts:
  dir: /Users/you/.miniforge/artifacts

Config Files:
  User: /Users/you/.miniforge/config.edn ✓
  Defaults: <embedded>

Override with environment variables:
  MINIFORGE_LLM_BACKEND=openai
  MINIFORGE_LLM_MODEL=gpt-4
```

### Get/Set Config Values

```bash
$ miniforge config get llm.backend
:codex

$ miniforge config set llm.max-tokens 8000
✅ Set llm.max-tokens to 8000
   Saved to: /Users/you/.miniforge/config.edn

$ miniforge config get llm.max-tokens
8000
```

## Configuration Precedence

Configuration is merged from three sources (in order of precedence):

1. **Environment variables** (highest priority)
   - `MINIFORGE_LLM_BACKEND`
   - `MINIFORGE_LLM_MODEL`
   - `MINIFORGE_LLM_TIMEOUT`
   - `MINIFORGE_MAX_ITERATIONS`
   - etc.

2. **User config file** (`~/.miniforge/config.edn`)
   - Created automatically on first use
   - Editable with `miniforge config edit`

3. **Default config** (lowest priority)
   - Embedded in the CLI
   - Provides sensible defaults

## Backend Specifications

### Claude (Anthropic)

- **Provider**: Anthropic
- **Type**: CLI-based (streaming)
- **Requires**: `claude` CLI installed
- **Install**: `npm install -g @anthropic-ai/claude-cli`
- **API Key**: `ANTHROPIC_API_KEY` (optional, handled by CLI)
- **Models**: claude-sonnet-4, claude-opus-4, claude-haiku-4

### Codex (OpenAI)

- **Provider**: OpenAI
- **Type**: HTTP API (streaming)
- **Requires**: `OPENAI_API_KEY` environment variable
- **Install**: Just set the API key (no CLI needed)
- **Models**: gpt-4-turbo, gpt-4, gpt-3.5-turbo
- **Docs**: https://platform.openai.com/api-keys

### OpenAI (GPT-4)

- **Provider**: OpenAI
- **Type**: HTTP API (streaming)
- **Requires**: `OPENAI_API_KEY` environment variable
- **Install**: Just set the API key (no CLI needed)
- **Models**: gpt-4-turbo, gpt-4, gpt-3.5-turbo
- **Default Model**: gpt-4
- **Note**: Same as Codex but defaults to gpt-4 instead of gpt-4-turbo

### Ollama (Local Models)

- **Provider**: Ollama
- **Type**: HTTP API to localhost (streaming)
- **Requires**: Ollama installed and running
- **Install**: `brew install ollama`
- **Models**: codellama, llama2, mistral
- **Endpoint**: http://localhost:11434/api/chat

### Cursor (Cursor AI)

- **Provider**: Cursor
- **Type**: CLI-based (non-streaming)
- **Requires**: `cursor-cli` installed
- **Install**: https://cursor.sh
- **Models**: cursor-default

### Echo (Testing)

- **Provider**: Test
- **Type**: Built-in (non-streaming)
- **Purpose**: Testing and development
- **Always available**: Yes

## File Locations

### User Config

```
~/.miniforge/config.edn
```

### Component Locations

```
components/config/              # Config management component
  src/ai/miniforge/config/
    user.clj                    # User config operations
    interface.clj               # Public API

bases/cli/src/ai/miniforge/cli/
  backends.clj                  # Backend discovery
  config.clj                    # Config CLI commands
  main.clj                      # CLI dispatch (updated)

components/llm/src/ai/miniforge/llm/protocols/impl/
  llm_client.clj                # Backend implementations (updated)
```

## Implementation Details

### Line Counts (per spec)

- **Phase 1**: ~20 lines (CLI dispatch fix)
- **Phase 2**: ~200 lines (user config)
- **Phase 3**: ~400 lines (backend implementations)
- **Phase 4**: ~250 lines (backend discovery)
- **Phase 5**: ~350 lines (config CLI)
- **Total**: ~1,220 lines

All files follow Rule 720 (≤400 lines) and Rule 210 (≤3 layers).

### Testing

All commands tested and working:

- ✅ `miniforge config backends` - Lists all backends with status
- ✅ `miniforge config backend <name>` - Sets backend with validation
- ✅ `miniforge config list` - Shows all configuration
- ✅ `miniforge config get <key>` - Gets specific value
- ✅ `miniforge config set <key> <value>` - Sets value
- ✅ `miniforge config init` - Creates user config
- ✅ `miniforge config validate` - Validates config
- ✅ Helpful errors for missing API keys
- ✅ Helpful errors for unavailable backends
- ✅ Backend status checking
- ✅ Environment variable overrides
- ✅ Config file auto-creation

## Success Metrics

✅ **Users can set backend in <30 seconds**

- Just run `miniforge config backend <name>`

✅ **Zero confusion about what backends exist**

- `miniforge config backends` shows all with status

✅ **Clear path from 'want Codex' to 'using Codex'**

- Error messages guide users step-by-step

✅ **No need to read docs or edit files**

- All operations via CLI commands

✅ **Helpful errors guide users to success**

- Shows installation instructions
- Shows API key setup steps
- Shows documentation links

## Next Steps

The configuration system is complete and ready for use. Possible future enhancements:

1. **Azure OpenAI backend** - Add support for Azure OpenAI endpoints
2. **Backend health checks** - Periodic checks for backend availability
3. **Model selection UI** - Interactive model picker
4. **Config validation on save** - Validate config when editing
5. **Config templates** - Predefined config templates for common setups

## Migration Notes

- **Existing users**: Environment variables (`MINIFORGE_LLM_BACKEND`) continue to work
- **New users**: User config created automatically on first use
- **Backward compatibility**: All existing workflows continue to work

## Conclusion

The frictionless configuration system transforms Miniforge's UX from "configuration as a rite of passage" to "configuration as a first-class feature." Users can now:

- Discover all available backends instantly
- Understand requirements and installation steps
- Switch backends with a single command
- Get helpful, actionable error messages
- Configure everything without reading documentation

This is the UX standard we want for all Miniforge features.
