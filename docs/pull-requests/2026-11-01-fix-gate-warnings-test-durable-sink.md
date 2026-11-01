<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# test(workflow): gate-warning pass cases use an acknowledging stream

**Tier:** main is red
**Theme:** semantic merge conflict between #2303 and #2311

## Problem

`apply-gate-validation-keeps-gate-warnings` fails on main, with 3
assertions in `phase_transitions_test.clj`.

PR #2311 changed what counts as an acknowledged gate decision: a decision
now counts only once a durable sink has written it. #2311 updated the
neighbouring approved-verdict test to give its stream a `durable-sink`.
PR #2303 added two pass-path cases whose ctx streamed to `{:sinks []}`. The
two PRs each passed CI separately. #2303 merged after #2311 without CI
re-running against it. With no durable sink, the decision is
unacknowledged, the phase is projected to `:failed`, and the assertions
that a warning leaves the phase passing fail. That is the intended
behavior introduced by #2311; the test context was out of date.

## Changes

- A test helper, `acknowledging-ctx`, builds the same ctx #2311 gave the
  sibling test: a stream with one `events/durable-sink`.
- Both pass-path cases use it. The assertions are unchanged.

## Testing

The test file reads cleanly. The workflow tests need Clojars
dependencies, which are unreachable from the authoring environment, so
CI is the run. The fix copies the sibling test's ctx exactly, and that
test is green on main.
