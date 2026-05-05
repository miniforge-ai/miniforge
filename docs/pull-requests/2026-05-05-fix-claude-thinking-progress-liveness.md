# Fix Claude Thinking Progress Liveness

## Summary

This PR fixes the next live Claude dogfood failure after `#774`.

The planner no longer dropped raw stream evidence on timeout, which exposed the
remaining defect clearly: Claude was still actively streaming `assistant`
thinking events, but Miniforge did not count those events as semantic progress.
That let `:plan` false-time out during the gap between startup and the first
tool-use or visible text delta.

## Changes

- treat Claude `assistant` thinking blocks as semantic activity
- refresh the progress monitor on thinking events without surfacing thinking
  text as user-visible content
- keep tool-use / heartbeat / thinking progress on the same semantic liveness
  path in the stream bridge
- add regression coverage for:
  - parsing Claude thinking events
  - resetting stream progress from thinking-only activity

## Validation

- `clj-kondo --lint` on touched files
- focused `ai.miniforge.llm.interface-test`
- focused `ai.miniforge.llm.progress-monitor-test`

## Dogfood Context

The preceding live failure looked like:

- workflow reached `:plan`
- Claude emitted a real `assistant` event with `thinking` content
- progress monitor still reported `:chunks 0` and `:unique-chunks 0`
- workflow failed with `Adaptive timeout: Stagnation timeout`

This PR closes that specific false-idle gap so Claude planning can stay alive
while it is reasoning but has not yet emitted tool-use or visible text.
