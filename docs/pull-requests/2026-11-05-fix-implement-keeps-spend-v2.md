<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(implement): keep the implementer's tokens and cost through the curator's result

## Overview

Part of #2378. The implement phase now reports the implementer turn's tokens and cost whenever
the curator's result stands in for the implementer's. That covers files collected from the
worktree, the no-files failure, and the defensive fallback. This was the main cause of
`Tokens: 0` in the workflow summary.

## Motivation

`enter-implement` runs the curator after every implementer turn and then picks a result:

1. When the curator found files, the result is the implementer's with
   `(update :metrics merge (:metrics curator-result))`. The curator's metrics come from
   `response/success`, whose builder fills `:tokens 0 :duration-ms 0` next to the file counts.
   The merge replaced the implementer's measured tokens with that 0.
2. When the curator found no files (`:curator/no-files-written`), and in the defensive `:else`,
   the result is the curator's alone, with no implementer spend.

With native Codex, the session writes files itself and the curator collects them, so every such
run reached the first branch. The phase recorded `:tokens 0`, and the workflow summary printed
`Tokens: 0` while `:implementer/llm-called` logged nonzero tokens. A run that failed on an empty
diff lost its consumption entirely.

## Changes in Detail

1. `implement-result/with-implementer-spend` (new, layer 0) merges the implementer's `:tokens`
   and `:cost-usd` metrics back onto a result.
2. `enter-implement` applies it after the curator-success merge, on the curator-terminal result,
   and on the `:else` result. The curator's file counts still merge as before.

## Testing Plan

1. New `implement_spend_test.clj` runs `enter` and `leave` of the implement phase with a curator
   stub shaped like `curate :implement`'s real responses: its `artifact-metrics` success and its
   `empty-diff-error`. The existing `mock-curator-success` in `implement_test.clj` passes the
   implementer's metrics through, which is why the zeroing was never caught.
2. The new test fails on main (phase tokens are 0 instead of 1500) and passes with the fix,
   together with `implement-test` and `review-repair-loop-test`.

## Deployment Plan

No configuration change. Implement phase metrics and `:execution/metrics` now include the
implementer's spend on these paths.

## Related Issues/PRs

1. miniforge-ai/miniforge#2378: the cost and tool tracking issue.
2. #2397: a rejected implementer turn keeps its cost (the agent half of this fix, already on main).
3. #2390: each phase's spend counts once in the execution metrics.
4. #2401: the `implement.clj` split this builds on.

## Checklist

1. [x] Regression test fails on the previous code
2. [ ] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
