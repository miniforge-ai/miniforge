<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(llm): an unknown LLM cost stays unknown, not 0.0 or a flat-rate guess

## Overview

Part of #2378. A streamed LLM response now carries `:cost-usd` only when the cost is known. The
CLI reported it, or the effective model has a price in the cost table. The implement phase no
longer invents a flat per-token cost when the agent reported none. An unpriced run's phase
metrics carry tokens and no cost, which the workflow marks `:cost-unavailable?` (#2388) and the
summary prints as `Cost: unavailable`.

## Motivation

1. `handle-streaming` in `llm/protocols/impl/llm_client.clj` resolved the cost as
   `(or reported (cost/estimate-cost usage model))`. `estimate-cost` returns `0.0` for a model
   missing from the cost table, which includes the Codex CLI's default model (no `-m`). Every
   native Codex response therefore carried `:cost-usd 0.0`, a measured-looking zero.
2. `leave-implement` fell back to `(* tokens 0.000015)` when the agent result had no cost. That
   is a guess presented as the phase's cost.
3. The estimate used the client's `:model` only, not a per-request model.

## Changes in Detail

1. `llm_client.clj`, `handle-streaming`: the cost is the CLI-reported one, else an estimate when
   usage is present and the effective model (`(:model request-with-model)`) has pricing, else
   nil. Both the success and the error responses carry `:cost-usd` only when it is non-nil.
2. `implement.clj`, `leave-implement`: the cost is the agent's cost or nil. Phase metrics carry
   `:cost-usd` only when it is known; an agent's `:cost-usd nil` is dropped. `:execution/metrics
   :cost-usd` adds `0.0` for an unknown cost.
3. `http_backend.clj`, `http-complete`: a provider response that bills nothing gets the
   cost-table estimate when the request's model is priced. Every HTTP provider except OpenRouter
   bills nothing on the response. Before this PR the implement phase's flat rate filled that
   gap. Without the estimate, an `:anthropic-api` run on a priced model would show no cost.

## Testing Plan

1. New `components/llm/test/ai/miniforge/llm/unknown_cost_test.clj`. A Codex run on the default
   model reports tokens and no `:cost-usd`, on success and on failure. A run on `gpt-5.4` gets
   the table estimate.
2. `interface_test.clj`: the cut-run test now names a priced model (`claude-sonnet-4-6`). It
   asserts that a cut run's cost is reported, which now requires a price.
3. `implement_test.clj`: three assertions expected the flat-rate figure, or any number, as phase
   cost for an agent that reported none. They now expect no phase cost and `0.0` added to the
   execution total.
4. `http_providers_test.clj`: an Anthropic response on `claude-sonnet-4-6` gets the table
   estimate, and one on an unpriced model gets no `:cost-usd`.
5. On main the new and changed assertions fail. With the fix they pass, together
   with `interface-test`, `cost-test` and the workflow's `cost-unavailable-metrics-test`.

## Deployment Plan

No configuration change. Responses from unpriced models no longer carry `:cost-usd 0.0`.
Implement phase metrics no longer carry a flat-rate cost. A cost budget can only count costs that
are known. Spend on an unpriced model no longer moves the cost total toward a budget. Before,
the implement phase moved it by the flat rate.

## Related Issues/PRs

Merge after #2408. With this PR alone, a native Codex run whose files the curator collected still
records 0 tokens. The workflow marks a phase unpriced only when its tokens are above 0.

1. miniforge-ai/miniforge#2378: the cost and tool tracking issue.
2. #2388: marks unpriced spend `:cost-unavailable?` and prints the cost as unavailable.
3. #2383: native Codex tool calls.
4. #2401: the `implement.clj` split this builds on.

## Notes

1. The non-streaming retry after an empty CLI stream (`complete-impl`) is not estimated here.
2. `estimate-cost` bills cached input tokens at the full input rate, so an estimate for a run
   with a large prompt cache is high. That is existing behaviour, not changed here.

## Checklist

1. [x] Regression tests fail on the previous code
2. [ ] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
