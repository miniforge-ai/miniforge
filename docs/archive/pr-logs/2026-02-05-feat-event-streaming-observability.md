<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(event-stream): Add real-time workflow observability

**Date:** 2026-02-05
**Branch:** feat/dag-orchestration
**Status:** Ready for Review

## Problem

Workflows run silently with no real-time visibility into what's happening during LLM calls,
phase transitions, or agent execution. When the standard-sdlc workflow hangs, there's no way
to see why - no streaming output, no phase progress, nothing.

## Solution

Add a new `event-stream` component that provides real-time observability:

1. **N3-compliant event bus** - Pub/sub system with filtering, per-workflow sequencing
2. **Console streaming** - LLM output streams to terminal in real-time
3. **SSE endpoint** - Web dashboard can subscribe to live workflow events
4. **Phase events** - Automatic emissions on phase start/complete

## Implementation

### New Component: `components/event-stream/`

| File | Purpose |
|------|---------|
| `interface.clj` | Public API: create-event-stream, publish!, subscribe!, event constructors |
| `core.clj` | Event bus implementation with N3-compliant envelopes |
| `schema.clj` | Malli schemas for all event types |
| `interface_test.clj` | 68 assertions across 9 tests |

### Key Event Types (N3 Spec)

- `workflow/started`, `workflow/completed`, `workflow/failed`
- `workflow/phase-started`, `workflow/phase-completed`
- `agent/chunk` - Streaming LLM output tokens
- `agent/status` - Real-time progress updates
- `llm/request`, `llm/response` - LLM call tracking

### Modified Files

| File | Change |
|------|--------|
| `workflow_runner.clj` | Create event stream, wire streaming callback, emit lifecycle events |
| `context.clj` | Pass `:on-chunk` and `:event-stream` through context |
| `phase/plan.clj` | Emit phase-started/completed events |
| `phase/implement.clj` | Emit phase-started/completed events |
| `web.clj` | Add SSE endpoint at `/api/workflows/:id/stream` |
| `deps.edn` | Add event-stream to :dev and :test aliases |

## Key Design Decisions

1. **Optional dependency** - Phase files use `requiring-resolve` to avoid hard dependency on event-stream
2. **Backward compatible** - Streaming is opt-in via `:on-chunk` in context
3. **N3 compliant** - All events follow `specs/normative/N3-event-stream.md`
4. **Uses modern httpkit API** - `as-channel` instead of deprecated `with-channel`

## Testing

```bash
# Run event-stream tests
clojure -M:dev:test -e "(require '[ai.miniforge.event-stream.interface-test]) \
  (clojure.test/run-tests 'ai.miniforge.event-stream.interface-test)"
# => 9 tests, 68 assertions, 0 failures

# Lint check
clj-kondo --lint components/event-stream
# => 0 errors, 0 warnings
```

## Usage

### CLI Streaming (automatic)

```bash
# Workflows now stream LLM output to console
clojure -M:dev -m ai.miniforge.cli.main run specs/my-task.spec.edn
# Output appears in real-time as agent thinks
```

### SSE Subscription (web dashboard)

```javascript
const eventSource = new EventSource('/api/workflows/' + workflowId + '/stream');
eventSource.addEventListener('agent/chunk', (e) => {
  const event = JSON.parse(e.data);
  console.log(event.chunk.delta); // Stream token
});
```

### Programmatic

```clojure
(require '[ai.miniforge.event-stream.interface :as es])

(def stream (es/create-event-stream))
(def wf-id (random-uuid))

;; Subscribe to events
(es/subscribe! stream :my-sub
  (fn [event] (println (:message event))))

;; Publish events
(es/publish! stream (es/workflow-started stream wf-id))
(es/publish! stream (es/phase-started stream wf-id :plan))

;; Create streaming callback for agents
(def on-chunk (es/create-streaming-callback stream wf-id :planner {:print? true}))
```

## Next Steps

Use this to diagnose why standard-sdlc workflows are hanging - the streaming output will show exactly where execution
stalls.

## References

- N3 Event Stream Spec: `specs/normative/N3-event-stream.md`
- Existing event bus: `pr-lifecycle/events.clj` (pattern reference)
- Agent streaming: Agents already check for `:on-chunk` in context
