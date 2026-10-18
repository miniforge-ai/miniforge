<!--
  Title: HTTP backends report why generation stopped
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(llm): HTTP backends report the stop reason

Branch: `feat/llm-http-stop-reason`

## Summary

The CLI backends put `:stop-reason` on a response (`"end_turn"`,
`"max_tokens"`, `"max_turns"`). The HTTP backends did not. Their
extractors returned content, usage, and cost, and
`parse-provider-response` set no `:stop-reason`. An answer cut at the
output cap looked the same as a complete one.

This matters most for a reasoning model behind an HTTP backend. The
request's `:max-tokens` is a hard cap there and it includes reasoning
tokens. A long read can spend the whole cap and return half an answer,
or none. Thesium's rank read had three such unexplained failures on
2026-10-03.

## Change

1. **Every HTTP response carries `:stop-reason` when the provider gave
   one.** Each extractor reads its provider's field:

   | provider | field |
   |---|---|
   | Anthropic | `stop_reason` |
   | OpenAI, OpenAI-compatible, OpenRouter | `choices[0].finish_reason` |
   | Gemini | `candidates[0].finishReason` |
   | Ollama | `done_reason` |

2. **One normalizer for all backends.** `normalize-codex-finish-reason`
   becomes `normalize-finish-reason` and gains Gemini's spellings.
   `"stop"` and Gemini's `"STOP"` become `"end_turn"`. `"length"` and Gemini's
   `"MAX_TOKENS"` become `"max_tokens"`. Any other spelling passes
   through unchanged (`"content_filter"`, `"refusal"`, `"SAFETY"`), so
   Anthropic's value is returned as given. The Codex path keeps its
   behavior; its tests pass unchanged. A missing or non-string reason
   yields no key.

3. **An empty 200 keeps its stop reason.** A 200 with no text is still
   the `empty_success_output` failure. That failure now carries
   `:stop-reason` beside `:cost-usd`. A read that spent its whole cap
   on reasoning arrives this way, and so does a refusal; the stop
   reason tells them apart.

`extraction` takes its optional fields as one map (`:usage-extras`,
`:cost-usd`, `:finish-reason`) instead of two positional arguments.

## Not changed

- Request bodies, routing, timeouts, and error classification.
- `complete-stream` on an HTTP backend still returns the non-streaming
  result, so it carries `:stop-reason` too.
- The Claude CLI's result frame has a `subtype` (for example
  `error_max_budget_usd`) that the stream parser does not read. That is
  a separate gap.

## Stratum lint

`llm_client.clj` is over the layer budget on main (SL003) and stays so.
This PR adds no layer and moves no function between layers.
`normalize-finish-reason` is a `case` with no dependencies, so it stays
in Layer 0 where the Codex normalizer was. A first draft kept the
spellings in a separate map, lifting the normalizer one layer.
The autofix then reordered about 300 unrelated lines, so the map was
folded back into the function. The commit used the documented
`MINIFORGE_STRATUM_BUDGET_MODE=warn` opt-out for the pre-existing
SL003, with every other pre-commit check passing.

## Tests

`http-providers-test`, `interface-test`, `network-health-test`: 106
tests, 553 assertions, no failures. New tests:

- one row per provider spelling, thirteen in all, each asserting the
  canonical `:stop-reason` on a successful result;
- a response that names no finish reason, or a null one, carries no
  `:stop-reason` key;
- an empty answer with `finish_reason: "length"` is an error that
  carries `"max_tokens"` and the billed cost;
- `complete` and `complete-stream` both return the stop reason.

Not verified against a live provider in this PR. The field names are
from each provider's published response shape.
