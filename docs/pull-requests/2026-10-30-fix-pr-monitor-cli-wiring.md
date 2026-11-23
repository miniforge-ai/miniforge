<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Give mf pr monitor a generate-fn and trusted authors

## Overview

`mf pr monitor` now creates its PR monitor with an LLM `generate-fn` and a trusted-author list,
and refuses the two unsafe paths that wiring makes reachable.

## Motivation

The CLI monitor set neither `:generate-fn` nor `:trusted-authors`. Without a `generate-fn` the
handlers skip every change request and question (`:no-generate-fn`) and classify by keyword only.
Without `:trusted-authors` (honoured by the monitor loop since #2193) nobody outside GitHub's
OWNER/MEMBER/COLLABORATOR associations can be trusted.

## Changes in Detail

1. Mechanical: `commands/pr_monitor.clj` used 4 layers (rule 210 budget is 3) only because its
   constants and `sh!` seam lived with their callers; they move unchanged to `pr-monitor-primitives`.
2. New `pr-monitor-agent`: `:generate-fn` from the configured LLM backend and `:trusted-authors`
   from a new `--trusted-authors a,b` flag, used by both the fresh (`--author`) and work-list
   resume paths. The fix arity (fix task, loop context) runs the task-executor implementer, the same
   generate-fn the PR lifecycle controller uses.
3. The text arity (classify a comment, answer a question) calls only completion-only (HTTP)
   backends, with the configured `[:llm :model]`. CLI backends can edit files (codex runs with
   `--sandbox=workspace-write` and no approvals, cursor with `--force`, claude honours user
   permission settings), so with one of them the text arity returns nil and `mf pr monitor` prints
   that comments are classified by keyword and questions go unanswered.
4. New `monitor-fix-guard/fix-refusal`, before any budget is spent: a change-request fix runs only
   when the worktree has the PR's head branch checked out at the PR's head commit. The fix loop
   commits in that worktree and pushes the branch by name; a matching name alone would let a fork's
   PR from its own `main` commit onto, and push to, this repository's `main`.

## Testing Plan

1. `pr-monitor-test`: both monitor-starting paths receive a `generate-fn` and the parsed trusted
   set (fails on main); the LLM client and config are stubbed, so no test reads host state.
2. `pr-monitor-agent-test`: flag parsing, text arity on an HTTP backend, nil on failure, agent
   backends never called, configured model sent, fix arity delegates to the implementer.
3. `monitor-fix-guard-test`: refusals for no generate-fn, another branch, the same branch name at
   another commit; the handler refuses before spending budget; branch and commit read from a real
   temporary git repository.
4. All 37 pr-lifecycle test namespaces pass, including the monitor-state persistence tests updated
   to run on the PR's head.

## Deployment Plan

No migration. Operators who want LLM classification and answers configure an HTTP backend
(`anthropic-api`, `openai-api`, ...); change-request fixes need the monitor to run in a checkout of
the PR's head.

## Related Issues/PRs

- #2267 (merged): task-executor passes the task and worktree to the implementer.
- #2259: fills the fix task's `:description`.
- Follow-ups: cross-repository detection from `isCrossRepository` in the poller; retrying refused
  comments (the loop advances its watermark before routing; `monitor_loop.clj` needs a split first);
  a tool-disabled completion API in the llm component.

## Checklist

- [x] New assertions fail on main
- [x] Stratum lint, clj-kondo, commit and PR budgets pass
- [x] Copilot review comments answered
