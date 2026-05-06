<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix Claude result-event content recovery

## Summary

Recover final assistant text from Claude CLI's terminal `result` event
when no assistant text delta was emitted earlier in the stream.

This closes a real failure mode for workflow-native Career profile
synthesis, where Claude returned a successful `end_turn` response but
Miniforge accumulated zero content and downstream JSON parsing failed.

## Changes

- `miniforge/components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj`
  - teach `parse-claude-stream-line` to surface `:final-content` from
    the terminal `result` event
  - teach `stream-with-parser` to seed accumulated content from
    `:final-content` when no assistant delta arrived
- `miniforge/components/llm/test/ai/miniforge/llm/interface_test.clj`
  - cover `result` events that carry authoritative final text
  - cover result-only content recovery in the stream accumulator

## Why

Recent Claude CLI output can place the actual final text in the
terminal `result.result` field even when the assistant event stream
does not emit a text delta. Miniforge was only harvesting usage from
that event, so downstream workflow code saw `nil` or empty content even
though the run had succeeded.

## Verification

- `git diff --check`
- `clojure -M:test -e "(require 'ai.miniforge.llm.interface-test) (let [result (clojure.test/run-tests 'ai.miniforge.llm.interface-test)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))"`
- `clojure -M -e \"(require 'ai.miniforge.llm.protocols.impl.llm-client 'cheshire.core) (let [line (cheshire.core/generate-string {:type \\\"result\\\" :result \\\"{\\\\\\\"ok\\\\\\\":true}\\\" :stop_reason \\\"end_turn\\\" :usage {:input_tokens 9 :output_tokens 3}}) parsed (ai.miniforge.llm.protocols.impl.llm-client/parse-claude-stream-line line) content (atom \\\"\\\") usage (atom nil) cost (atom nil) tools (atom []) session-id (atom nil) stop-reason (atom nil) turns (atom nil) chunks (atom []) handler (ai.miniforge.llm.protocols.impl.llm-client/stream-with-parser #'ai.miniforge.llm.protocols.impl.llm-client/parse-claude-stream-line (fn [chunk] (swap! chunks conj chunk)) content usage cost tools session-id stop-reason turns)] (handler line) (when-not (= @content \\\"{\\\\\\\"ok\\\\\\\":true}\\\") (System/exit 1)))\"`

## Result

Workflow callers can now rely on Miniforge to recover Claude result
content even when the assistant stream emitted no text delta. This
restores the expected LLM contract for downstream structured-output
parsers.
