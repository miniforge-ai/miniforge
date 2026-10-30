<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Give the CI fixer the failed-job logs, or do not run it

## Overview

The CI fixer now receives a prompt that carries the failed jobs' logs, and refuses to run when
there are no logs to fix from.

## Motivation

The fix task passed to `generate-fn` was `{:task/id ... :task/type :fix}`. The task-executor
`generate-fn` (the live path from `task-executor/runner.clj`) builds its prompt from the task's
description, so the fixer's prompt held no task text and no CI output. `build-fix-prompt` was
never called on this path.

## Changes in Detail

1. `run-fix-loop` builds the prompt from the fix context, and `generate-fix` passes it as the fix
   task's `:description`. Review and conflict fixes get their prompt the same way.
2. The `:ci-failure` prompt includes the failed-job logs under a "Failed job logs:" heading.
3. `fix-ci-failure` refuses to start when the logs are blank: `schema/failure` with
   `:reason :ci-logs-unavailable`, and `generate-fn` is never called.
4. The `:review-changes` prompt reads every comment shape its callers pass: extracted changes,
   triage results wrapping a parsed `:comment`, and the PR monitor's `{:body :path :line}`.
5. Test refactor: the gh 2.102 replay helpers move to `gh-replay-test-support`.

`controller.clj` is untouched (7 layers against the rule-210 budget of 3), so the controller still
reports `:reason :fix-failed`; the fix-loop result carries `:ci-logs-unavailable`.

## Testing Plan

`ci-fix-prompt-test` drives `controller/run-lifecycle!` against replayed gh 2.102 output with the
inner loop stubbed: a failing Actions check reaches the fixer with a non-blank prompt containing the
job's log; a failure with no fetchable log does not call the fixer; every review-comment shape
renders. The worker is cancelled on timeout so stubs never outlive the test. This is the
qualification scenario "CI failure gives the fixer a non-empty prompt with failed-job logs"
(`ai.miniforge.pr-lifecycle.ci-fix-prompt-test`).

## Deployment Plan

No configuration or data migration.

## Related Issues/PRs

- #2257 (base): the monitor fetches the logs.
- #2267 (merged): task-executor passes the task and the caller's worktree to the implementer.

## Checklist

- [x] Scenario tests fail against the previous `fix_loop.clj`
- [x] Stratum lint, clj-kondo, commit and PR budgets pass
- [x] Copilot review comments answered
