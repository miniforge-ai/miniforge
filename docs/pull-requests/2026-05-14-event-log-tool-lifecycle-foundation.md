<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Event log tool lifecycle foundation

**Branch:** `dogfood/event-log-tool-lifecycle-foundation`
**Spec:** `work/event-log-tool-visibility.spec.edn`

## Summary

Adds the event-stream foundation for tool-call visibility:

- bounded content digesting with SHA-256, original byte size, and preview text
- `:agent/tool-call-started`, `:tool/call-completed`, and `:workflow/phase-heartbeat` event constructors
- Malli schema coverage and event-type registry entries for those events
- public interface re-exports for the new constructors and digest helper
- unit tests for digest behavior, optional tool metadata, schema validation, and event publication

This is the first implementation task from the dogfood plan. It does not yet wire callbacks into agent execution, add
CLI rendering, or add observer alert rules.

## Dogfood Notes

The dogfood run selected `work/event-log-tool-visibility.spec.edn` as the highest-priority active spec and reached
`:verify` for task 1:

- `:explore` passed.
- `:plan` passed and produced a six-task plan.
- `:implement` passed for task 1 and persisted a task bundle at
  `~/.miniforge/checkpoints/33b59cd5-1667-4520-895f-6b5b2efa341c/task-0558b217.bundle`.
- The LLM did not submit the MCP artifact file, so Miniforge used the fallback file-artifact path and recovered the
  changed files.
- I interrupted the original verify phase after misreading the quiet broad test run as a stall. Manual and hook
  verification later showed the relevant tests pass; the dogfood finding is that verify-phase progress is still too
  opaque during long test runs.

## Testing

- `git commit` hook:
  - commit budget: 28 / 200 lines for schema/test polish
  - `clojure -M:poly check` passed with existing warning 207s
  - clj-kondo passed on changed Clojure files
  - stable-derived test plan passed in 10m37s
  - GraalVM/Babashka compatibility passed, 6 tests / 499 assertions
- Event-stream coverage observed in the hook:
  - `ai.miniforge.event-stream.new-events-test`: 13 tests / 40 assertions
  - `ai.miniforge.event-stream.digest-test`: 10 tests / 20 assertions
  - existing event-stream namespaces passed in the stable-derived project runs

## Review Focus

- Confirm the event payload shapes are the right contract before callback wiring depends on them.
- Confirm optional tool metadata is acceptable for lifecycle events emitted from partial or failed tool invocations.
- Confirm digest preview limits and byte-size semantics are sufficient for audit/debug use without leaking full tool
  payloads.
