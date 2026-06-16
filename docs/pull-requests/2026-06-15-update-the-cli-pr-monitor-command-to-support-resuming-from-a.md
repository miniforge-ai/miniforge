<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Update the CLI `pr monitor` command to support resuming from a

**PR:** [#1203](https://github.com/miniforge-ai/miniforge/pull/1203)
**Branch:** `mf/update-the-cli-pr-monitor-command-to-sup-8fb9f6aa`

## Summary

Update the CLI `pr monitor` command to support resuming from a

## Files Changed

- `bases/cli/src/ai/miniforge/cli/main/commands/pr_monitor.clj` (modify)
- `bases/cli/test/ai/miniforge/cli/main/commands/pr_monitor_test.clj` (create)
- `bases/cli/resources/config/cli/messages/en-US.edn` (modify)
- `bases/cli/src/ai/miniforge/cli/main.clj` (modify)
- `bases/cli/src/ai/miniforge/cli/main/commands/pr.clj` (modify)
- `bases/cli/src/ai/miniforge/cli/main/commands/pr_monitor.clj` (modify)

## Test Results

_No test artifacts available._

## Review Decision

**Decision**: approved

In-scope pr.clj change is a thin delegation — requires pr-monitor and forwards opts unchanged. No logic errors in scope. Single nit: the docstring on the boundary function should carry the contract rather than redirecting. Verdict is :approved on in-scope issues alone. Out-of-scope advisory: resume-from-worklist! has an 8-level nesting anti-pattern that should be decomposed, and the fresh-monitor path lacks a test.

### Known issues (non-blocking)

Merged with 1 unresolved non-blocking issue(s) recorded for follow-up:

- `bases/cli/src/ai/miniforge/cli/main/commands/pr.clj` — pr-monitor-cmd docstring says 'See that namespace for full docs' — redirects readers rather than carrying the boundary contract at the call site. Per rules 009/600, a public boundary function states the contract: what the caller gets, which exit codes fire, and under what conditions.
