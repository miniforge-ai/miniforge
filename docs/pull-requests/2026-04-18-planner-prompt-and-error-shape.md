<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix planner prompt + canonical invoke error shape

## Context

PR #574 surfaced the planner's actual failure in the event log:

```clojure
:anomaly/category :anomalies.agent/invoke-failed
:anomaly/message  "Plan generation failed: EDN parse did not succeed"
:llm-content-preview "I need to explore the codebase... Let me look at the
                      key files... Now let me look at the agent interface...
                      Let me check the planner system prompt..."
```

The LLM is narrating its exploration, calling tools, then **ending the
turn without either (a) a final EDN plan or (b) an MCP artifact
submission call**. The runtime's `parse-plan-response` gets narrative
prose, can't read it as EDN, and throws.

## Root cause — two producer bugs

### Bug 1 — planner prompt doesn't enforce the closing contract

The planner's "Plan Output" section was only 2 lines:

> Output your plan as a Clojure map in your final message. The plan will
> be extracted from your response text. Wrap it in a ```clojure code fence.

And the user-prompt's closing instruction was similarly soft. The LLM
read this as a suggestion, not a hard requirement. Turns ending in
narration are a real and recurring failure mode.

### Bug 2 — `invoke-impl` returns non-canonical error shape

When the planner threw, `agent.protocols.impl.specialized/invoke-impl`
caught the exception and returned:

```clojure
{:status :error
 :error (ex-message e)           ; ← a STRING
 :metrics {:duration-ms ...}}
```

The canonical `response/error` shape is `{:error {:message string :data
map}}`. Hand-rolling the catch branch produced internal shape drift.
This is why PR #574's `summarize-error` couldn't pull the error into
the DAG diagnostic — `(:message <string>)` is nil. Same feedback
pattern you gave me earlier: fix the producer, don't tolerate drift at
the consumer.

## What changed

### Planner prompt — hard-enforce the closing contract

`components/agent/resources/prompts/planner.edn`:

- Replaced the 2-line "Plan Output" section with an expanded **"Plan
  Submission — REQUIRED"** section.
- Two explicit options: (A) call the artifact MCP tool, (B) final
  assistant message contains the plan EDN in a `clojure` code fence.
- Explicit "do NOT end your turn with prose narration" with examples
  of the phrases that trigger the failure.
- Added a **"Failure Modes to Avoid"** section citing the real observed
  failures (narration-only ending, missing code fence, wrong top-level
  form).
- Turn-budget section updated: reserve at least 1 turn for final
  submission; if at 14 reads, next move is the plan, not another read.

`components/agent/src/ai/miniforge/agent/planner.clj`:

- User-prompt closing block is now explicit: "Your FINAL assistant
  message MUST be the plan", lists both submission paths, cites the
  anomaly name that rejections throw, and tells the LLM to stop and
  replace prose if it catches itself writing prose-only.

### `invoke-impl` uses `response/error`

`components/agent/src/.../protocols/impl/specialized.clj`:

- Catch branch now calls `(response/error e {:data (ex-data e) ...})`
  instead of hand-rolling `{:error (ex-message e)}`.
- Exception message, `ex-data`, and `:role` all end up on the
  canonical `{:error {:message :data}}` shape. `summarize-error` can
  pull it; event sinks and failure classifiers get a consistent shape.

### Tests

`components/agent/test/.../invoke_error_shape_test.clj` — 5 new tests,
9 assertions covering:

- `:error` is a map, not a string
- `:message` matches the exception message
- `:data` preserves `ex-data`
- `:role` recorded on `:error :data`
- `:duration-ms` at `:metrics`
- explicit smoke test that `:error` is not a string (the observed
  regression)

All pre-existing tests still pass (4 env-promotion, 6 phase-outcome,
15 dag-activation-diagnostics, 5 new invoke-error-shape).

## Expected behavior on next run

1. If the planner fails, `:result :error :message` carries the actual
   error string, and `:dag/diagnostic :result/error :error/message`
   surfaces it.
2. With the stronger prompt, the LLM is more likely to produce a final
   EDN plan (or call the MCP artifact tool). Fewer dead turns.
3. If narration-only endings continue despite the prompt, the
   diagnostic will tell us exactly which anomaly triggered and we can
   consider a runtime retry with a constrained re-prompt.

## Follow-up

- If the prompt fix doesn't reduce narration-only endings, consider
  **runtime retry**: detect `:anomalies.agent/invoke-failed` from
  parse failure, retry once with a constrained "emit plan only"
  prompt. That's targeted behavior, not a guess — wait for the next
  run's data.
- The checkpoint/resume spec (PR #574) remains a separate work stream.
