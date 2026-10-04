<!--
  Title: OpenRouter HTTP backend + cache, reasoning, and cost usage fields
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(llm): OpenRouter backend; keep cache, reasoning, and cost usage

Branch: `feat/llm-openrouter-backend`

## Summary

Thesium Career needs a hosted route to the cheaper models (DeepSeek,
Kimi, Gemini) that works in a sandboxed build. Today the only route to
OpenRouter is the OpenCode CLI, a host CLI the App Store build cannot
exec. `:openai-compat` (#1428, #1440) can reach it with a base-URL
override and an optional key, but nothing names it, the key is not
required, and the response's cost and cache figures are dropped.

Three commits:

1. **Usage fields.** The OpenAI wire shape reports cached input,
   cache-write input, and reasoning output tokens under
   `usage.*_details`; the Codex CLI reports `cached_input_tokens` and
   `reasoning_output_tokens` on `turn.completed`. The client kept only
   the two totals. `:usage` now also carries

   | key | meaning |
   |---|---|
   | `:cached-input-tokens` | input served from the prompt cache |
   | `:cache-write-input-tokens` | input written to the prompt cache |
   | `:reasoning-output-tokens` | output spent on reasoning |

   On these backends each is a **subset** of `:input-tokens` or
   `:output-tokens`, unlike the Claude CLI's `:cache-read-input-tokens`
   and `:cache-creation-input-tokens`, which sit beside `:input-tokens`.
   The new keys have their own names so no consumer double-counts, and
   `:tokens` is unchanged. Only numeric values are assoc'd. An HTTP
   extractor may also return the cost a provider says it billed;
   `parse-provider-response` carries it as `:cost-usd`, the key the CLI
   path already uses.

2. **`:openrouter` backend.** A keyed HTTP backend at
   `https://openrouter.ai/api/v1/chat/completions`, key from
   `OPENROUTER_API_KEY` or client `:api-key`, required (fails closed
   with `missing_api_key`). It rides the OpenAI body with
   `usage: {include: true}` and parses `usage.cost` into `:cost-usd`.
   Model ids are OpenRouter's (`vendor/model`); no default model.

3. **HTTP idle timeout.** `http-post-request` called http-kit with no
   timeout, so http-kit's 60 s idle default applied. These calls are
   non-streaming: the server sends nothing until the model is done, and
   a long completion (a Thesium rank read is ~170K tokens in and takes
   minutes) was cut at 60 s on every direct provider backend. The call
   now passes `:idle-timeout` from `client-defaults.edn`
   (`[:http :idle-timeout-ms]`, 600000, the stream ceiling's size). The
   connect timeout keeps its default. Reading a client default lifts
   `http-post-request` from Layer 1 to Layer 4; the function is moved,
   not changed beyond the one option.

## Verified against the live API

One call through the real endpoint (key injected by `op run`, never
in the shell) returned HTTP 200 with `max_completion_tokens` and
`usage.include` accepted, and a `usage` block carrying `cost`,
`prompt_tokens_details.cached_tokens`,
`prompt_tokens_details.cache_write_tokens`, and
`completion_tokens_details.reasoning_tokens`. The Codex field names
were read from a live `codex exec --json` `turn.completed` event
(CLI 0.144.6).

## Stratum lint

`llm_client.clj` is over the layer budget on main (SL003, eleven
layers) and stays so; this PR adds no layer. The autofix placed
`openai-usage-extras` in Layer 0 and changed nothing else; that
placement is committed. Commits used the documented
`MINIFORGE_STRATUM_BUDGET_MODE=warn` opt-out for the pre-existing
SL003, with every other pre-commit check passing.

## Tests

`http-providers-test`, `network-health-test`, `interface-test`: 102
tests, 503 assertions. New: OpenRouter wiring, request body, round
trip (URL, Bearer key, usage flag, usage breakdown, billed cost,
`:tokens` unchanged), missing key fails closed before any request;
OpenAI usage details kept and no nil keys without them; Codex
cached-input and reasoning counts kept, absent when unreported; the
provider call passes the configured idle timeout and leaves the
connect timeout alone.
