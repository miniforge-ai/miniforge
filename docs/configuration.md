<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Configuration Management

> This document covers configuration for the **Miniforge** SDLC product (CLI/TUI). MiniForge Core exposes configuration
seams that products customize via their own `app.edn`; the settings below are Miniforge-specific defaults and overrides.

## Where configuration comes from

1. Built-in defaults shipped on the classpath.
2. The user config file, `~/.miniforge/config.edn` (`$MINIFORGE_HOME/config.edn`
   when `MINIFORGE_HOME` is set).
3. A repository's `.miniforge/config.edn`, for `:policy-packs` overrides.
4. The environment variables below, which override all of the above.

A spec's `:spec/llm-backend` overrides the backend for that run.

Manage the user config file with `mf config`:

```bash
mf config list              # current settings
mf config get llm.backend
mf config set <key> <value> # e.g. key llm.backend
mf config backend claude    # sets llm.backend after checking the backend is installed
mf config backends          # backends and whether each is available
mf config init | edit | reset | validate
```

## Environment variables

- `MINIFORGE_HOME` - Directory for the user config file and artifacts
  (default: `~/.miniforge`). Checkpoints and worktrees go where
  `:workflow :checkpoint-root` and `:workflow/worktree-root` in the config
  say, which default to `~/.miniforge/checkpoints` and `~/.miniforge/worktrees`.
- `MINIFORGE_LLM_BACKEND` - LLM backend for the run (default: `opencode`). Run
  `mf config backends` for the list.
- `MINIFORGE_LLM_MODEL` - Model for every role; sets `llm.model`. See [Models](#models).
- `MINIFORGE_AGENT_THINKING_MODEL` - Model for the planner and architect roles.
- `MINIFORGE_AGENT_EXECUTION_MODEL` - Model for every other role.
- `MINIFORGE_GIT_TOKEN`, `GH_TOKEN` - Token the release phase uses to open the
  PR, checked in that order before `gh auth token`.
- `MINIFORGE_RUNTIME` - Container runtime; see [Container runtime](#container-runtime).
- `MINIFORGE_NETWORK_MONITOR` - Set to `off` (or `false`, `0`, `no`) to turn
  off the network-drop monitor. During a streaming CLI call (`:claude`,
  `:codex`) it probes the provider every 10 s and stops the call after 3
  failed probes in a row. The probe uses the CLI's configured endpoint.
  It checks `ANTHROPIC_BASE_URL`, Bedrock and Vertex settings, or `OPENAI_BASE_URL`; otherwise it uses the provider's
  API host.
  It follows `HTTPS_PROXY`, `HTTP_PROXY`, and `NO_PROXY` as the agent CLI does.

## Models

Every role runs on the run's backend: `llm.backend`, unless the run
chooses another (the spec's `:spec/llm-backend`, for one). Choosing a
model never moves a role to another backend.

The planner and architect use the thinking tier; every other role uses
the execution tier. A role's model is the first of these that is set:

1. The tier's own setting: `[:agents :default-models :thinking]` or
   `[:agents :default-models :execution]` (`MINIFORGE_AGENT_THINKING_MODEL`,
   `MINIFORGE_AGENT_EXECUTION_MODEL`).
2. `llm.model` (`MINIFORGE_LLM_MODEL`), for both tiers.
3. Miniforge defaults to Claude Opus 4.6 for thinking and Claude Sonnet 4.6 for execution.
   This applies to `opencode` (`anthropic/...` ids), `claude`, and `anthropic-api`.
4. None: the backend's own default. The `codex` CLI uses its built-in
   default model. Miniforge runs it with `--ignore-user-config`, ignoring the model in `~/.codex/config.toml`.
   Set `llm.model` instead.
   The `cursor` CLI uses its own default and `ollama` uses `codellama`. The
   `openai-api`, `gemini-api`, `openrouter` and `openai-compat` backends
   need a model, so set `llm.model` for them.

The shipped defaults set no model. An environment variable overrides the
config file key it maps to. A blank setting counts as unset, and
`mf config set llm.model nil` clears one. `claude` and `anthropic-api`
take an `anthropic/...` id without the prefix.

A setting naming a catalog model the backend does not serve is skipped.
One warning on stderr names the key, model, and backend; the next source applies. Examples are `gpt-5.4` on `claude`, or
a
Claude id on `codex` or `gemini-api`. `opencode`, `openrouter`,
`openai-compat` and `cursor` route to many providers and take any model.
A model the catalog does not know is used as given, since it may be newer
than the catalog.

A config file that an older `mf config init`, `set`, `backend` or `edit`
created may carry `:llm {:model "anthropic/claude-sonnet-4-5"}`. On
`opencode`, `claude` and `anthropic-api` it applies to every role; on
`codex`, `openai-api`, `gemini-api` and `ollama` it is skipped as above.
Remove it to get the defaults.

`mf config set` stores `llm.model` and the two tier keys as strings, so a
model id needs no quoting.

```bash
mf config backend codex                # roles use codex's built-in model
mf config set llm.model gpt-5.4        # every role uses "gpt-5.4"
MINIFORGE_AGENT_THINKING_MODEL=gpt-5.4-pro mf run spec.edn  # planner only
```

## Timeouts and budgets

No environment variable or `config.edn` key sets LLM timeouts, token budgets or
iteration limits for a run. Earlier versions of this page listed
`MINIFORGE_LLM_TIMEOUT`, `MINIFORGE_LLM_LINE_TIMEOUT`, `MINIFORGE_LLM_MAX_TOKENS`,
`MINIFORGE_MAX_ITERATIONS`, `MINIFORGE_MAX_TOKENS`, `MINIFORGE_FAILURE_STRATEGY`,
`MINIFORGE_ARTIFACTS_DIR` and `MINIFORGE_META_*`; nothing read them, and
miniforge no longer accepts them.

The limits that do apply live in shipped resources:

1. LLM call ceilings: `components/llm/resources/llm/client-defaults.edn`
   (`:stream` and `:http`), overridden per role by the `:prompt/progress-monitor`
   block in `components/agent/resources/prompts/*.edn`.
2. Phase retries: `:budget {:iterations N}` on a phase in the workflow
   definition.

## Container runtime

Per [N11-delta](../specs/normative/N11-delta-runtime-adapter.md),
miniforge runs each task inside an OCI container in governed mode
(`--execution-mode governed`). The default, local mode, runs each task
in a git worktree on the host, with the operator's privileges and no
isolation. Two layers configure
the runtime today; the third (file config) is a documented follow-up.

### Today: env var + descriptor config map

The runtime selector reads its config from a flat map with three keys
that the descriptor consumes directly:

```clojure
;; Shape consumed by ai.miniforge.dag-executor.protocols.impl.runtime.descriptor/make-descriptor
{:runtime-kind :podman
 :executable   "/opt/homebrew/bin/podman"
 :docker-path  "/usr/local/bin/docker"}  ; legacy alias for :executable when :runtime-kind is :docker
```

Keys:

- `:runtime-kind` — `:docker` or `:podman` (Phase 2 supported).
  `:nerdctl` is known but not yet supported.
- `:executable` — explicit CLI path; defaults to the kind's binary
  name resolved via `PATH`.
- `:docker-path` — legacy alias for `:executable` when `:runtime-kind`
  is `:docker`. Preserved for the pre-N11-delta config shape.

The CLI exposes one source today: the `MINIFORGE_RUNTIME` environment
variable, which sets `:runtime-kind`:

```bash
MINIFORGE_RUNTIME=podman mf runtime info
MINIFORGE_RUNTIME=docker mf doctor
```

When no kind is set, miniforge auto-probes Podman, then Docker. First
whose `info` command succeeds wins. If `MINIFORGE_RUNTIME` names a
runtime that isn't available, miniforge fails loud rather than falling
back — the whole point of explicit configuration is honoring your
choice.

### Forthcoming: `miniforge.edn` file-config block

Per N11-delta §2.1 the canonical config block lives under
`:miniforge/runtime` in the user/repo profile:

```clojure
;; .miniforge/config.edn — wire-up tracked as a follow-up
{:miniforge/runtime
 {:kind       :podman
  :executable "/opt/homebrew/bin/podman"}}
```

The selector accepts the flat map today; the loader that strips the
`:miniforge/runtime` namespace and threads it into the selector lands
in a follow-up. Until then, use `MINIFORGE_RUNTIME=...`.

### Inspecting the resolved runtime

```bash
mf runtime info       # descriptor as data (kind, exec, version, capabilities)
mf doctor             # full system check, includes runtime block
```

## Usage examples

```bash
# Use Codex CLI instead of OpenCode for one run
MINIFORGE_LLM_BACKEND=codex mf run examples/workflows/implement-feature.edn

# Keep config and artifacts for a sandbox run apart
MINIFORGE_HOME=/tmp/mf-sandbox mf run examples/workflows/implement-feature.edn
```
