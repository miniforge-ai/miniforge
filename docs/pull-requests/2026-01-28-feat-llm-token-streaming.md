<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: implement token streaming for LLM component

## Overview

Add token streaming capability to the LLM component, enabling real-time output as LLM
responses are generated. This allows agents and workflows to display progressive output
during long-running LLM operations, improving user experience and enabling real-time
progress monitoring.

## Motivation

Currently, LLM responses are only available after the entire completion finishes. For
long-running operations (planning, implementation, testing), users have no feedback
during generation. Streaming enables:

- Real-time progress indicators in the CLI and web UI
- Better user experience during agent execution
- Ability to monitor token generation rate and detect stalls
- Foundation for future features like streaming to workflow observers

## Changes in Detail

### Protocol & Interface

- Add `complete-stream*` method to `LLMClient` protocol (components/llm/src/ai/miniforge/llm/interface/protocols/llm_client.clj:7-19)
- Expose `complete-stream` and `chat-stream` in public API (components/llm/src/ai/miniforge/llm/interface.clj:97-131)
- Streaming callback signature: `(fn [chunk])` where chunk is `{:delta "..." :done? bool :content "accumulated"}`

### Backend Configuration

- Make streaming backend-agnostic with `:streaming?` and `:stream-parser` configuration keys
- Claude backend configured with:
  - `--output-format=stream-json` for JSON streaming format
  - `--include-partial-messages` to receive delta events
  - `--verbose` flag (required for streaming with `--print`)
- Stream parser handles Claude's stream format: `{"type":"stream_event","event":{"delta":{"text":"..."}}}`
- Graceful fallback for non-streaming backends (calls `on-chunk` once with full result)

### Implementation

- `stream-exec-fn` - Executes command and streams output line-by-line (components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj:94-117)
  - Uses `java.io.BufferedReader` for line-by-line processing
  - Accumulates lines for final result while calling callback for each
  - Returns same format as `default-exec-fn` when complete

- `complete-stream-impl` - Protocol implementation with backend detection (components/llm/src/ai/miniforge/llm/protocols/impl/llm_client.clj:150-205)
  - Checks `:streaming?` flag and falls back to `complete-impl` if false
  - Parses each streamed line using backend's `:stream-parser`
  - Accumulates content across chunks
  - Sends final `{:done? true}` chunk when complete
  - Returns standard response format `{:success bool :content "..." :usage {...}}`

### Dependencies

- Added `org.clojure/data.json {:mvn/version "2.5.0"}` to components/llm/deps.edn for JSON stream parsing

### Testing

- Comprehensive streaming tests in components/llm/test/ai/miniforge/llm/interface_test.clj:
  - `complete-stream-test` - Tests streaming with mock clients, chunk handling, and error cases
  - `chat-stream-test` - Tests convenience function with/without options
  - `stream-accumulation-test` - Verifies content accumulates correctly across chunks
  - `stream-parser-test` - Tests Claude JSON parsing, invalid input handling, and edge cases
- Tests use mock exec-fn to simulate streaming output without actual CLI calls
- All existing tests continue to pass (2,300+ assertions)

## Testing Plan

- [x] Unit tests for streaming API (`complete-stream`, `chat-stream`)
- [x] Unit tests for stream parsing (valid JSON, malformed input, non-stream events)
- [x] Unit tests for content accumulation across chunks
- [x] Unit tests for error handling in streaming mode
- [x] Verify all existing LLM tests continue to pass
- [x] Lint and pre-commit validation passes
- [ ] Manual verification: Test streaming with live Claude CLI
- [ ] Integration: Wire streaming into planner agent as proof-of-concept
- [ ] Integration: Connect streaming to WorkflowObserver for UI updates

### Manual Testing

```clojure
;; Test streaming with Claude CLI
(require '[ai.miniforge.llm.interface :as llm])

(let [client (llm/create-client {:backend :claude})]
  (println "Streaming output:")
  (llm/chat-stream
    client
    "Count from 1 to 5, one number per line"
    (fn [{:keys [delta done?]}]
      (when-not done?
        (print delta)
        (flush)))
    {:system "Be concise."}))
```

## Deployment Plan

No deployment steps required. Changes are additive and backward-compatible:

- Existing code using `complete` and `chat` continues to work unchanged
- Streaming is opt-in via new `complete-stream` and `chat-stream` functions
- Non-streaming backends automatically fall back to single callback

## Next Steps

1. **Agent Integration** - Update planner/implementer/tester agents to use `complete-stream`
2. **Observer Integration** - Wire streaming callbacks to WorkflowObserver for real-time updates
3. **UI Indicators** - Add CLI and web UI progress indicators during LLM generation
4. **Performance Monitoring** - Add metrics for token generation rate and streaming latency
5. **Documentation** - Add streaming examples to component README

## Related Issues/PRs

- Foundation for N1 Conformance Tests workflow monitoring
- Enables real-time progress in workflow execution
- Prerequisite for agent streaming updates

## Checklist

- [x] Protocol method added to LLMClient
- [x] Public API functions (`complete-stream`, `chat-stream`) implemented
- [x] Backend configuration supports streaming flag and parser
- [x] Claude backend configured with stream-json format
- [x] Graceful fallback for non-streaming backends
- [x] Comprehensive unit tests covering streaming scenarios
- [x] Stream parser tests for JSON parsing and error handling
- [x] All existing tests pass
- [x] Linting passes with no errors
- [x] Dependencies added to deps.edn
- [ ] Manual testing with live Claude CLI
- [ ] Integration with at least one agent (planner/implementer/tester)
- [ ] WorkflowObserver integration for UI updates
