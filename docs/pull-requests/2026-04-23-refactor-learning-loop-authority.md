# refactor: make `agent.meta.loop` the learning-loop authority

## Overview

Remove the duplicated learning-loop implementation from `agent.meta-coordinator`
and make `agent.meta.loop` the single source of truth for learning-cycle
execution and context helpers.

## Motivation

The supervision split landed, but the agent layer still had two different
implementations of the learning loop:

- `agent.meta.loop`
- `agent.meta-coordinator`

That left duplicated ownership for the same control path and kept the public
`agent.interface.meta` API pointed at the wrong implementation.

## Changes In Detail

- Move the public learning-loop authority to `agent.meta.loop`
- Add context-backed helpers to `agent.meta.loop`:
  - `create-meta-loop-context`
  - `record-workflow-outcome!`
  - `run-cycle-from-context!`
- Add a compatibility arity on `run-meta-loop-cycle!` so the public interface
  can keep its current shape while delegating to the real learning-loop module
- Update `agent.interface.meta` so supervision functions still route to
  `meta-coordinator`, but learning-loop functions route to `meta.loop`
- Delete the duplicated learning-loop implementation from
  `agent.meta-coordinator`
- Extend `meta.loop` tests to cover context-backed metrics accumulation and
  context-driven cycle execution

## Testing Plan

- `clj-kondo --lint` on the touched `components/agent` namespaces
- `clojure -M:test -e "(require 'ai.miniforge.agent.meta.loop-test) ..."`
- `bb test components/agent`

## Checklist

- [x] Learning-loop execution has one implementation
- [x] Public `agent.interface.meta` learning APIs point to `agent.meta.loop`
- [x] Supervisor coordination remains in `agent.meta-coordinator`
- [x] Tests cover the context-backed learning-loop path
