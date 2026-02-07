# Miniforge Dogfooding Guide

## Overview

This guide explains how to have miniforge work on itself using the DAG orchestration system.

## Quick Start

### 1. Set Environment Variables

```bash
# Required
export GITHUB_TOKEN="ghp_your_token_here"
export ANTHROPIC_API_KEY="sk-ant-your_key_here"

# Optional
export MINIFORGE_MAX_PARALLEL=2
export MINIFORGE_MAX_TOKENS=1000000
export MINIFORGE_MAX_COST_USD=100.0
```

### 2. Prepare Task DAG

Tasks are defined in `dogfood-tasks.edn`. Current tasks:

- **task-1**: Wire in actual LLM backend to generate.clj
- **task-2**: Add integration tests (depends on task-1)
- **task-3**: Add metrics collection (depends on task-1)

### 3. Run Dogfooding Session

```bash
# Dry run (check configuration)
bb scripts/dogfood-dag.clj

# Full execution (when ready)
bb dogfood --dag dogfood-tasks.edn --monitor
```

## Monitoring

### Real-time Progress

```bash
# Watch logs
tail -f logs/dogfood-$(date +%Y%m%d)-*.log

# Watch task states
watch -n 5 'gh pr list | grep "task-"'

# Check DAG status
bb dogfood-status
```

### Monitoring Dashboard

The execution will show:

- ✅ Tasks completed (merged PRs)
- 🔄 Tasks in progress (open PRs, CI running)
- ⏸️  Tasks pending (waiting on dependencies)
- ❌ Tasks failed (with error details)

### Metrics Collected

Per task:

- Tokens used
- Cost (USD)
- Iterations (inner loop cycles)
- PR events (opened, CI runs, reviews, merged)
- Time to merge

## Safety & Control

### Manual Intervention

If you need to intervene:

```bash
# Pause execution (completes current tasks)
bb dogfood-pause

# Resume paused execution
bb dogfood-resume

# Cancel specific task
gh pr close <pr-number>

# Force stop all
pkill -f dogfood-dag
```

### Budget Limits

Execution automatically stops when:

- Max tokens exceeded
- Max cost exceeded
- All tasks completed
- Critical error encountered

### Review Gates

Configure auto-merge policy:

- `auto-merge: always` - Merge when CI passes
- `auto-merge: after-review` - Wait for human approval
- `auto-merge: never` - Manual merge only

Default: `after-review` (safe for dogfooding)

## Current Status

**Phase**: Setup
**Blockers**:

- ❌ GitHub token not configured
- ❌ Anthropic API key not configured
- ⚠️  LLM backend not wired in generate.clj (task-1 will fix this!)

**Next Steps**:

1. Set environment variables
2. Verify GitHub permissions (PR creation, merge)
3. Run dry-run to validate setup
4. Start dogfooding session with monitoring

## Architecture

```text
User
 └─> bb dogfood
      └─> Load dogfood-tasks.edn
           └─> task-executor/execute-dag!
                ├─> Task 1: Fix generate.clj (parallel with Task 3)
                ├─> Task 3: Add metrics (parallel with Task 1)
                └─> Task 2: Add tests (after Task 1 completes)
                     └─> Each task:
                          ├─> Generate code (agent + loop)
                          ├─> Create PR
                          ├─> Monitor CI
                          ├─> Handle reviews
                          └─> Auto-merge
```

## Expected Timeline

Based on similar tasks:

- **Task 1** (wire LLM): ~10-15 minutes
  - Code generation: 3-5 min
  - CI: 2-3 min
  - Review (if required): 5-10 min
- **Task 2** (add tests): ~15-20 minutes
  - More complex, multiple files
- **Task 3** (add metrics): ~10 minutes
  - Straightforward enhancement

**Total**: ~30-45 minutes for all 3 tasks

## Troubleshooting

### Task stuck in CI

```bash
# Check CI logs
gh pr view <pr-number> --web

# Restart CI if needed
gh pr comment <pr-number> -b "/rerun"
```

### Task failed repeatedly

```bash
# View error logs
bb dogfood-logs --task <task-id>

# Mark as skip and continue
bb dogfood-skip --task <task-id>
```

### Need to add task

```bash
# Edit dogfood-tasks.edn
vim dogfood-tasks.edn

# Reload (preserves running tasks)
bb dogfood-reload
```

## Post-Dogfooding

After successful completion:

1. Review merged PRs
2. Check metrics dashboard
3. Collect learnings for operator component
4. Plan next dogfooding session

## Example Session

```bash
$ export GITHUB_TOKEN="ghp_..."
$ export ANTHROPIC_API_KEY="sk-ant-..."
$ bb dogfood --dag dogfood-tasks.edn

🤖 Starting dogfooding session: dogfood-2026-02-06
📋 Tasks: 3 total, 2 can start immediately

[10:00:00] Task 1: Wire LLM backend → :implementing
[10:00:00] Task 3: Add metrics → :implementing
[10:03:45] Task 1: PR opened (#121)
[10:05:12] Task 3: PR opened (#122)
[10:05:45] Task 1: CI passed ✅
[10:06:23] Task 3: CI passed ✅
[10:08:15] Task 1: Merged! 🎉
[10:08:15] Task 2: Add tests → :implementing (unblocked)
[10:09:01] Task 3: Merged! 🎉
[10:13:30] Task 2: PR opened (#123)
[10:15:45] Task 2: CI passed ✅
[10:18:22] Task 2: Merged! 🎉

✨ Dogfooding complete!
   Tasks: 3 completed, 0 failed
   Time: 18m 22s
   Cost: $2.34
   PRs merged: #121, #122, #123
```
