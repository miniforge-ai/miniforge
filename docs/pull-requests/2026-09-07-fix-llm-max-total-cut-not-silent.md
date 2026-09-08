<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: a run cut at the LLM max-total ceiling is reported, accounted, and not promoted

## Overview

Trap bench series 9, run `baseline-trap-a-ry1` (2026-09-04, checkpoint
`feabb7f0-c85b-4320-853b-f5850695e002`): two implement turns given the
larger verify-failure prompts (35-40k chars) hit the LLM client's
10-minute `:max-total-ms` ceiling.

1. Iteration 3 (05:28:58 to 05:38:59) was cut mid-write after several
   `mcp__context__context_write` calls. `components/codex-gap/deps.edn`
   was left without its `:paths` line, and every later verify failed with
   `Error 110: Validation error in components/codex-gap/deps.edn`.
2. Iteration 5 (05:57:40 to 06:07:40) made ten `context_read` calls and
   was cut before any write.

In both cases the run log shows `implementer/file-artifact-fallback`
collecting the working directory, `implementer/llm-called` with
`{:success false, :tokens 0, :tools-called []}`, and
`agent/invoke-completed {:status :success}`. Nothing in the run log says
the run was cut.

## Motivation

Three defects, one per fix below.

1. The LLM client's timeout warning goes to the client's own logger, which
   is not the run log, and the implementer honored the file-artifact
   container-promotion precedence for every timeout type. A turn the
   client cut mid-write was promoted as a success.
2. The error response built for a cut run carried neither the tools the
   model had called nor the tokens it had consumed. The Claude stream
   parser only read usage from the final `result` frame, which a cut run
   never sees.
3. The implementer used the framework default ceiling. Planner and
   reviewer already carry `:prompt/progress-monitor` in their prompt
   config; the implementer had no block, so its ceiling was not
   configurable and its turns, which write many files, got the same
   10 minutes as a planning turn.

## Changes in Detail

### LLM client (`components/llm`)

1. `streaming-error-response` stamps `:llm/terminated-by` on the response
   for any known adaptive-timeout type. `:hard-limit` is reported as
   `:max-total`, the name of the knob that changes it. Other types keep
   their canonical name.
2. The error response carries `:usage` and `:tokens` when usage is
   present (same shape `llm-success` emits), and the streaming error
   branch attaches `:tools-called` and `:cost-usd` like the success
   branch does.
3. `parse-claude-stream-line` surfaces per-message `usage` and message
   id from `assistant` events. `stream-with-parser` folds them via
   `fold-message-usage`: input-side counts take the latest call (they
   describe that call's whole context, so summing them would push
   `context-overflow-by-usage?` into a false overflow on any long cut
   run), output tokens accumulate, and the message id dedupes the one
   event per content block Claude Code emits. The `result` frame's
   totals still replace the folded counts.
4. `log-streaming-result` takes the tool-call count and logs a cut run
   at warn with `:terminated-by`, `:elapsed-ms`, `:max-ms`, and
   `:tool-call-count`.

### Implementer (`components/agent`)

1. `invoke-implementer-session` passes a `:progress-monitor` built from
   `implementer.edn`'s new `:prompt/progress-monitor` block; the
   submission-recovery turn gets `:prompt/submission-retry-monitor`.
   Both go through the existing `prompts/load-progress-monitor`, the
   same path planner and reviewer use.
2. `implementer.edn`: `:prompt/progress-monitor` with
   `:max-total-ms 1800000` (30 minutes). Stagnation and activity spacing
   stay at the framework values. The recovery turn keeps 10 minutes.
3. `invoke-with-llm`: when the response carries
   `:llm/terminated-by :max-total`, the file artifact is not passed as
   the fallback, no submission-recovery turn runs, and the result is
   `result-boundary/error-response` with the llm-error (including its
   `:timeout` envelope) plus `:llm/terminated-by` and the paths written
   before the cut under `:partial-files`. The phase's existing
   `backend-timeout-in-result?` classifies it: retriable from the
   session checkpoint within the iteration budget, then the terminal
   `:implement/backend-timeout` verdict. A new warn,
   `implementer/llm-max-total-exceeded`, logs elapsed time, ceiling,
   tool-call count, and the partial files on the run log's logger.
   `implementer/llm-called` gains `:terminated-by`.
4. Stream-idle and stagnation keep the container-promotion precedence:
   there the model finished writing and hung.

### Messages

`:error/llm-max-total-exceeded` in the repo-index message catalog.

## Testing Plan

New tests:

1. `components/llm/test/.../interface_test.clj`: `terminated-by` mapping;
   `streaming-error-response` carries usage, tokens, and the marker;
   `log-streaming-result` warns with elapsed time and call count; a
   `complete-stream` run whose fake stream replays two `tool_use` events
   and then returns the hard-limit `timeout-result` reports both tools,
   positive tokens, cost, the marker, and the warn; per-message usage
   parsing; `fold-message-usage` latest/sum/dedupe; result-frame
   replacement through `stream-with-parser`.
2. `components/agent/test/.../implementer_test.clj`: a `:max-total` cut
   with files on disk is an error result carrying the timeout envelope,
   the marker, partial files, and tokens, logs the cut, does not log a
   file-artifact fallback, and runs no recovery turn; a stream-idle cut
   still promotes files; the implementer's `:progress-monitor` reaches
   the LLM client with a ceiling of at least 30 minutes.
3. `components/phase-software-factory/test/.../implement_test.clj`:
   the cut result is retriable within the iteration budget and terminal
   `:implement/backend-timeout` at the budget.

Run locally: every `components/llm` test namespace, the implementer,
planner, reviewer, and result-boundary namespaces, and the phase
implement namespace. 333 tests, 1760 assertions, green.

Not verified here: a live rerun of the trap bench series 9 prompt.

## Deployment Plan

No migration. The implementer's ceiling rises from 10 to 30 minutes; a
turn that would previously have been cut and promoted now either
finishes or fails as a backend timeout. `bb pre-commit`'s stratum gate
reports the pre-existing SL003 (over the layer budget) on
`llm_client.clj` and `implementer.clj`; both were over budget at the
merge base, so the commits use `MINIFORGE_STRATUM_BUDGET_MODE=warn`.

## Related Issues/PRs

1. Trap bench series 9 pre-registration, #1891.
2. Phase-timeout stack: `:implement/backend-timeout` verdict and
   `backend-timeout-in-result?` (2026-06-06).
3. Separate follow-up, not in this PR: `context_read` took about a minute
   per call on the same run (`implementer/context-cache-misses`), and the
   non-streaming `complete-impl` path still runs under `default-exec-fn`'s
   fixed 10-minute subprocess timeout with no progress monitor.

## Checklist

- [x] LLM client marks and logs a ceiling cut with elapsed time and call count
- [x] Cut runs report tools, tokens, and cost
- [x] Implementer fails a `:max-total` cut instead of promoting partial files
- [x] Implementer ceiling configurable via `implementer.edn`; default 30 minutes
- [x] Tests for all three
- [ ] Copilot review settled
- [ ] Merged
