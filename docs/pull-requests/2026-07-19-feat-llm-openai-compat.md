<!--
  Title: Generic OpenAI-compatible HTTP backend + Ollama num_ctx threading
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(llm): generic OpenAI-compatible backend; thread :num-ctx to Ollama

Branch: `feat/llm-openai-compat`

## Summary

The DMG (non-App-Store) distribution of Thesium Career supports "bring
your own LOCAL model." The local-inference ecosystem converged on the
OpenAI Chat Completions wire shape: LM Studio, llama.cpp's server,
vLLM, and newer engines (e.g. colibrì) all expose
`/v1/chat/completions`. Rather than one backend per engine, this PR
adds a single generic backend behind the `http-providers` registry
that #1420 introduced:

| Backend | Wire shape | Endpoint |
|---|---|---|
| `:openai-compat` | OpenAI Chat Completions | client `:base-url` → `MINIFORGE_OPENAI_COMPAT_BASE_URL` → LM Studio default (`http://localhost:1234/v1/chat/completions`) |

Second fix in the same component: Ollama silently truncates long
prompts to its own small default context window because nothing
threaded `num_ctx`. `create-client` now accepts `:num-ctx`, both
completion paths carry it beside `:model`, and `ollama-request-body`
emits it as `options.num_ctx`. Long-surface product reads (a rank
claim surface is 40–88K tokens) were previously silently truncated on
local Ollama runs.

## Design

- **Reuse over new shapes.** The provider entry reuses
  `openai-request-body` / `extract-openai` wholesale — the point of an
  OpenAI-compatible backend is that nothing wire-level is new. Only
  endpoint resolution and auth policy differ.
- **Credential-free by default, key honored when supplied.** Local
  servers are typically keyless, so the backend declares no
  `:api-key-env` (which would fail closed per #1420's contract). A
  client `:api-key` becomes a bearer header when present — vLLM behind
  a gateway still works.
- **Base-url resolution mirrors key resolution:** client config wins,
  env variable next, static default last (`resolve-base-url`;
  `request-endpoint` now takes the client config).
- **`:requires-model?` stays true.** Compatible servers accept a model
  field and several route on it; failing closed on a missing model
  beats serializing `"model": null`.

## Verification

`clojure -M:poly test` (changed bricks) green, including new coverage:
backend wiring shape, keyless round-trip (no Authorization header,
OpenAI body/parse reuse), key + base-url override, missing-model
fail-closed before transport, probe endpoint, `num_ctx` body emission
and client-config threading.
