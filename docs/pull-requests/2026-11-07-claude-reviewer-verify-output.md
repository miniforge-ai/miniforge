<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(agent): keep the test runner's raw output out of the reviewer prompt

## Overview

The reviewer prompt's "Test Results" section now shows verify's status,
summary and counts, without the test runner's raw output. A review after a
whole-suite verify fits the model's context window again.

## Motivation

A dogfood run of `work/workflow-resume-emits-resumed-event.spec.edn` on
2026-11-07 passed verify after four rounds. Review then failed without
calling the model:

```text
Reviewer prompt ~273044 est tokens exceeds the model context window 200000
even after shedding the artifact's file bodies to a manifest and splitting
policy addenda was not possible; deterministic gates still applied
```

The gate read this as `Review not approved (decision=nil)`, and the run went
back to implement.

Verify runs the whole suite (29,073 tests in that run) and records the
runner's output under `:metrics :test-output`. In that run it was about
840,000 characters. Review passes verify's metrics to the reviewer as `:task/tests`, and
`build-review-prompt` printed them whole with `pr-str`. Shedding moves file
bodies to a manifest but leaves the test results, so the prompt could not
fit. Every review after a whole-suite verify failed the same way.

## Changes in Detail

1. `components/agent/src/ai/miniforge/agent/reviewer/prompts.clj`:
   1. New private `format-test-results` (Layer 0). A string passes through as
      before. A map is printed without `[:metrics :test-output]`.
   2. `build-review-prompt` renders the `tests` section through it.
2. `components/agent/test/ai/miniforge/agent/reviewer/prompts_test.clj`: a
   regression test whose verify results carry runner output under
   `:metrics`. The prompt must show the summary and counts and must not
   contain the output or the `:test-output` key.

The fix is in the prompt builder, not in review's
`build-verify-review-input`. Review's namespace has five layers, so any edit
to it fails stratum-lint SL003 until the namespace is split.

Not changed: when no implement artifact resolves, `build-review-prompt`
renders the whole task map as the code to review. That copy still holds the
runner output. Reaching that path means review has nothing to review.

## Testing Plan

1. `ai.miniforge.agent.reviewer.prompts-test` and
   `ai.miniforge.agent.reviewer-test`: 45 tests, 204 assertions, 0 failures.
   With `prompts.clj` reverted, the new test fails 2 of its 4 assertions.
2. Real run data: the reviewer prompt was rebuilt from that run's checkpointed
   verify result and input, with a one-file stand-in artifact. The shed prompt
   went from 873,617 characters (about 218k estimated tokens) to 5,110
   characters (about 1.3k).
3. CI runs the full suite.

## Deployment Plan

None. Takes effect on the next run.

## Related Issues/PRs

1. Found while dogfooding `work/workflow-resume-emits-resumed-event.spec.edn`.
   That spec is still open, and the run will be repeated once this merges.
2. The implementer already gets a bounded excerpt
   (`implement-verify-output/failure-focused-output`).

## Checklist

- [x] Regression test fails on main and passes here
- [x] Checked against real run data
- [x] Rules review against the pinned standards
- [ ] CI green
- [ ] Copilot review addressed
