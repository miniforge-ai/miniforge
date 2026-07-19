<!--
  Title: Direct API-key HTTP provider backends for the llm component
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(llm): direct API-key HTTP provider backends (Anthropic/OpenAI/Gemini)

Branch: `feat/llm-http-providers`

## Summary

Phase 2 of the Thesium Career sandbox-clean workstream (its
`docs/sandbox-clean-workstream.md`, decision record §1.3): sandboxed
(Mac App Store) builds cannot run CLI-agent backends — a sandboxed
child process inherits the sandbox, so the user's `claude`/`codex` CLI
wakes up without its config, keychain, or file access. Those builds
need first-class HTTPS provider backends with user-supplied API keys
(BYOK). Per the architecture, the capability accrues to miniforge's
`llm` component rather than being patched into the product repo.

The existing HTTP path (`:cmd "http"`) served only the local,
credential-free Ollama endpoint; provider API-key routing was
deliberately delegated to OpenCode. This PR adds three direct
providers behind the same dispatch:

| Backend | API | Key env fallback |
|---|---|---|
| `:anthropic-api` | Anthropic Messages API | `ANTHROPIC_API_KEY` |
| `:openai-api` | OpenAI Chat Completions | `OPENAI_API_KEY` |
| `:gemini-api` | Google Generative Language `generateContent` | `GEMINI_API_KEY` |

Backend selection is unchanged product-side: thesium-workflows'
dormant BYOM hook (`THESIUM_LLM_BACKEND_OVERRIDE` /
`THESIUM_LLM_MODEL_OVERRIDE` in `synthesis_config.clj`) can now name
`anthropic-api` and reach a working backend.

## Design

- **Provider = body builder + response extractor.** Transport
  (`http-post-request`), error shaping, and the blank-content guard
  are shared (`parse-provider-response`); each provider contributes a
  request-body builder and a `{:content :usage}` extractor, joined in
  the `http-providers` registry that `http-complete` dispatches on.
- **Key resolution is config-first, env-fallback, fail-closed.**
  `:api-key` in the client config wins; otherwise the backend's
  `:api-key-env` variable. A missing key fails before any request
  with a canonical `:invalid-input` anomaly (`missing_api_key`) —
  this is the first `:anomalies/incorrect` producer in the brick, so
  the W2 `category->type` table grows by one row.
- **Client config now threads into the HTTP path.** `http-complete`
  and `http-stream-complete` take the client config; the client's
  `:model` is applied to HTTP requests the same way the CLI path
  already applied it (previously the HTTP path silently ignored it).
- **Blank 200s fail.** A provider 200 with no generated text (e.g. a
  refusal stop with empty content) surfaces as `empty_success_output`
  instead of flowing downstream as a silent success — same contract
  the CLI path's `success-response` already enforces.
- **No streaming.** The HTTP backends run behind the async job seam,
  so chunk streaming buys no user-visible latency; `complete-stream`
  falls back to one terminal chunk (pre-existing behavior, now with
  config threading).

## Changes

| File | Change |
|------|--------|
| `components/llm/resources/llm/backends.edn` | Three new backend entries (`:cmd "http"`, `:api-key-env`, Gemini's `:model-in-url?`) |
| `components/llm/resources/llm/client-defaults.edn` | `:http :anthropic` section — `api-version`, `default-max-tokens` (Anthropic's API requires `max_tokens`) |
| `components/llm/src/.../protocols/impl/llm_client.clj` | Provider body builders/extractors, shared `parse-provider-response`, `http-providers` registry, `resolve-api-key`, `request-endpoint`, `provider-headers`; `http-complete`/`http-stream-complete` take client config; `token-usage` nil-guard (fixes a latent NPE in `parse-ollama-response` when Ollama omits eval counts); `:anomalies/incorrect → :invalid-input` in the W2 table |
| `components/llm/src/.../protocols/records/llm_client.clj` | `create-client` accepts `:api-key` |
| `components/llm/src/.../interface.clj` | `create-client` docstring: HTTP backends + `:api-key` |
| `components/llm/test/.../http_providers_test.clj` | New: body builders, headers/endpoint capture via stubbed transport, response parsing, missing-key fail-closed, error paths, ollama nil-usage regression, client round-trip through `complete`/`complete-stream` |

## Test plan

- `bb test` (stable-derived changed-and-affected) green, including the
  new `http-providers-test` namespace — all transport stubbed, no
  network.
- `bb poly:check` clean — no new dependencies (httpkit + cheshire were
  already the transport for the Ollama path, and both are
  bb-compatible for the JVM-substrate swap).
- Downstream (Phase 2 gate, separate PRs): growth stage sandboxed via
  `THESIUM_LLM_BACKEND_OVERRIDE=anthropic-api` against a real key.
