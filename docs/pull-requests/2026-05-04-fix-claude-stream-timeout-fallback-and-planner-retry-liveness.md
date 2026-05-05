## Summary

Hardens Claude planner liveness in two places that showed up during dogfooding:

- if the streaming CLI path times out after emitting only startup-noise events,
  retry once through the non-streaming CLI path instead of failing immediately
- relax the planner submission-retry monitor so a legitimate silent `Write` turn
  is not killed after 30 seconds

## Problem

After `#774`, Claude got past the earlier thinking-only false stagnation failure,
but planning still failed in a narrower shape:

- the main planner turn completed exploration
- the submission-only retry turn started
- Claude emitted only `system` / `rate_limit_event` startup lines
- no semantic assistant text, tool-use, or result events arrived before the
  30s submission-retry stagnation threshold
- Miniforge failed the phase even though the backend may still have been able
  to complete through the plain CLI path

That made the runtime too brittle for silent Claude turns.

## Changes

### LLM streaming fallback

In `components/llm`:

- add `silent-stream-timeout?`
- detect the case where streaming timed out with:
  - `exit = -1`
  - timeout info present
  - blank parsed content
  - zero tool calls
  - no usage/result event
- retry once through `complete-impl` in that case
- log the fallback reason distinctly as `:silent-stream-timeout`

This extends the existing `:empty-stream-success` fallback instead of creating
another one-off planner workaround.

### Planner retry monitor

In `components/agent/resources/prompts/planner.edn`:

- bump `:prompt/version` to `2.4.1`
- widen `:prompt/submission-retry-monitor` from:
  - `:stagnation-threshold-ms 30000`
  - `:max-total-ms 120000`
- to:
  - `:stagnation-threshold-ms 90000`
  - `:max-total-ms 180000`

This keeps the submission-only retry strict, but no longer assumes Claude will
surface semantic stream output within 30 seconds.

## Validation

- `clj-kondo --lint` on touched files
- targeted `ai.miniforge.llm.interface-test`
- full `bb pre-commit`
- isolated rerun of `ai.miniforge.workflow.merge-parent-branches-integration-test`
  to prove the first hook failure was an unrelated temp-worktree collision

## Dogfood notes

This branch was cut from the live Claude dogfood investigation in
`/private/tmp/mf-dogfood-claude-thinking-2026-05-05`.

Observed failure before the fix:

- planning timed out with `Stagnation timeout: no progress for 30027ms`
- preserved `:raw-stdout` contained only startup noise (`system`,
  `rate_limit_event`)
- no semantic Claude stream events arrived before timeout

The fix is intended to let that run survive silent retry turns and preserve a
clean stamped revision for the next dogfood pass.
