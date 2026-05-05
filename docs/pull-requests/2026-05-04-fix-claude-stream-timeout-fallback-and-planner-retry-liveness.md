## Summary

Hardens Claude planner execution in two places that showed up during dogfooding:

- if Claude streaming produces only startup-noise events, preserve liveness and
  recover through the existing non-streaming fallback instead of failing early
- if the planner returns prose like "Plan submitted..." without actually
  submitting EDN, force the existing submission-only retry path instead of
  hard-failing the phase

## Problem

After `#774`, Claude got past the earlier thinking-only false stagnation failure,
but planning still failed in two narrower shapes:

- startup-liveness failure:
  - Claude emitted only `system` / `rate_limit_event` startup lines
  - no semantic assistant text, tool-use, or result events arrived before the
    planner stagnation threshold
  - Miniforge treated provider-side pacing as a dead stream
- submission failure after a long-running turn:
  - Claude eventually returned prose-only content beginning with
    `Plan submitted...`
  - no `.miniforge/plan.edn` was written and no fallback EDN was returned
  - Miniforge hard-failed on EDN parse instead of invoking the existing
    submission-only retry

That made the runtime too brittle for silent Claude turns.

## Changes

### LLM streaming fallback and liveness

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
- treat Claude `rate_limit_event` as stream liveness rather than ignoring it
  as a no-op

This extends the existing `:empty-stream-success` fallback instead of creating
another one-off planner workaround.

### Planner retry policy

In `components/agent/src/ai/miniforge/agent/planner.clj`:

- broaden planner submission-retry eligibility
- retry not only on failed timeout/CLI responses with prose in `stdout`, but
  also on successful prose-only responses that produced neither submitted plan
  nor parseable EDN
- log the retry reason distinctly as `:non-edn-plan-response` when the model
  narrated submission without actually submitting

This reuses the existing submission-only retry path instead of adding another
planner-specific repair loop.

### Planner main-turn monitor

In `components/agent/resources/prompts/planner.edn`:

- bump `:prompt/version` to `2.4.2`
- widen `:prompt/progress-monitor` from:
  - `:stagnation-threshold-ms 60000`
  - `:max-total-ms 240000`
- to:
  - `:stagnation-threshold-ms 120000`
  - `:max-total-ms 360000`

This keeps the planner bounded, but stops assuming Claude will surface semantic
activity inside one minute during provider-lag or cold-start turns.

## Validation

- `clj-kondo --lint` on touched files
- targeted `ai.miniforge.llm.interface-test`
- targeted `ai.miniforge.agent.planner-test`
- full `bb pre-commit`
- isolated rerun of `ai.miniforge.workflow.merge-parent-branches-integration-test`
  to prove the first hook failure was an unrelated temp-worktree collision

## Dogfood notes

This branch was cut from the live Claude dogfood investigation in
`/private/tmp/mf-dogfood-claude-thinking-2026-05-05`.

Observed failures before the fix:

- startup-liveness case:
  - planning timed out with `Stagnation timeout: no progress for 60231ms`
  - preserved `:raw-stdout` contained only startup noise (`system`,
    `rate_limit_event`)
  - no semantic Claude stream events arrived before timeout
- prose-only submission case:
  - planner ran for ~5 minutes and returned `Plan submitted...`
  - no plan artifact was written
  - runtime failed with `EDN parse did not succeed` instead of forcing the
    submission-only retry

The fix is intended to survive provider-side pacing at startup and recover from
non-EDN planner narration, leaving a clean stamped revision for the next
dogfood pass.
