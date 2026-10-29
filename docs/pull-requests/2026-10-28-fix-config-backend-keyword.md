<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: config set stores backends as keywords; an unknown backend stops the run

## Overview and motivation

`mf config set llm.backend opencode`, the CLI's own usage example, stored the
symbol `opencode` (the value went through `edn/read-string`). The next run's
`llm/create-client` rejected it, `create-llm-client` caught that, printed
"agents will use fallback mode" and returned nil, the backend preflight was
skipped, and the planner failed later with a no-LLM-backend anomaly. A
mistyped `MINIFORGE_LLM_BACKEND` or `--backend` took the same path.

## Layer and dependencies

CLI adapter. Stacks on the shared config writer PR (same files).

## Changes

1. `parse-config-value` keeps a bare word as a string (a model id such as
   `anthropic/claude-sonnet-4-5` was a symbol too); `coerce-config-value`
   makes it a keyword under `llm.backend`, `workflow.failure-strategy` and
   `model-selection.strategy`.
2. `get-llm-backend` reads a string or symbol as the keyword it names, so a
   config the old `config set` wrote keeps working.
3. `create-llm-client` throws an `:invalid-input` anomaly with a plain message
   naming the backend, the known backends and `config backend <name>`, instead
   of returning nil.

## Verification plan

Tests (each fails on main): `config set llm.backend opencode` stores
`:opencode` and a model id stays a string; `get-llm-backend` on a symbol and
a string; an unknown `--backend` throws naming it and the known ones; a
symbol backend in config still yields a client. 247 tests in 26 CLI
workflow-runner, run, resume, chain and plan namespaces pass.

## Scope and deployment

`run`, plan execution and `run --resume` print the error and exit 1 (by code
reading of run.clj's try+). `mf resume`, `mf workflow run` and `mf chain run`
still exit 0 on failure (pre-existing, main.clj).
