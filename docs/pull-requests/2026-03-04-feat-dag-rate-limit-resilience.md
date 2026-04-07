# feat: DAG rate limit resilience — failover + graceful pause + resume

**Branch:** `feat/dag-rate-limit-resilience`
**Base:** `refactor/phase-status-and-neutral-result`
**Date:** 2026-03-04

## Overview

When Claude CLI hits rate limits mid-DAG execution, the orchestrator now detects the
rate limit, attempts backend failover (if configured), or gracefully pauses and emits
checkpoint events so the workflow can be resumed later without losing completed work.

## Motivation

Dogfooding `dashboard-production-ready.spec.edn` produced a 15-task DAG. 7 tasks
succeeded, then Claude CLI hit rate limits. The remaining 8 tasks all failed because:

1. Rate limit messages (`"You've hit your limit · resets 2pm"`) weren't detected —
   error patterns in `external.edn` didn't match Claude CLI's actual format
2. The implement phase retried 5 times with zero delay, burning all iterations in ~10s
3. No backend failover was attempted despite `backend_health.clj` having full logic
4. No way to resume — completed work was lost
5. Dependent tasks transitively failed immediately

## Changes

### 1. Failover policy in user config (`default-user-config.edn`)

Added two new keys to `:self-healing`:

- `:allowed-failover-backends []` — empty by default (pause, don't failover). User opts
  in by listing backends like `[:openai :ollama]`
- `:max-cost-per-workflow nil` — cost ceiling; pauses even if failover backends available

### 2. Rate limit error patterns (`external.edn`)

- Added 5 new patterns matching actual LLM CLI rate limit messages
- Added `:rate-limit? true` metadata to each, plus the existing `"API rate limit exceeded"` pattern
- Patterns: `you've hit your limit`, `rate?limit`, `quota?exceeded`, `429|Too Many Requests`,
  `resets \d+[ap]m`

### 3. New module: `dag_resilience.clj` (~140 lines)

Three-layer module extracted to keep `dag_orchestrator.clj` under 400 lines:

- **Layer 0** — `rate-limit-error?`: checks DAG error results against rate-limit patterns
- **Layer 1** — `attempt-backend-switch`: failover with cost ceiling + allowed-backends filter;
  `emit-dag-task-completed!` / `emit-dag-paused!`: event emission for checkpointing
- **Layer 2** — `analyze-batch-for-rate-limits`: categorizes batch results;
  `handle-rate-limited-batch`: orchestrates the failover-or-pause decision

Uses `requiring-resolve` for self-healing and event-stream dependencies (matching existing pattern).

### 4. DAG orchestrator loop changes (`dag_orchestrator.clj`)

- Added `dag-execution-paused` result constructor (`:paused? true`, `:pause-reason`)
- `execute-dag-loop` now:
  - Seeds `completed-ids` from `:pre-completed-ids` (resume support)
  - Tracks `current-backend` through the loop
  - After each batch, calls `resilience/analyze-batch-for-rate-limits`
  - On rate limit: calls `handle-rate-limited-batch` → continue (failover) or pause
  - Emits `dag/task-completed` events per completed task for checkpointing
- `execute-plan-as-dag` accepts `:pre-completed-ids` from context

### 5. DAG resume in `resume.clj`

- `extract-completed-dag-tasks`: collects task IDs from `:dag/task-completed` events
- `extract-dag-pause-info`: finds last `:dag/paused` event
- `reconstruct-context` now returns `:completed-dag-tasks`, `:dag-paused?`, `:dag-pause-reason`
- `resume-workflow` threads `:pre-completed-dag-tasks` into pipeline opts

### 6. Threading pre-completed-ids at call sites

- `execution.clj` (`try-dag-execution`): passes `pre-completed-dag-tasks` from execution opts
- `configurable.clj` (`execute-dag-for-plan`): passes from context
- `plan_executor.clj` (`execute-plan`): passes from opts

## Files Changed

| File | Type | Description |
|------|------|-------------|
| `resources/config/default-user-config.edn` | Modified | Add failover config keys |
| `components/agent-runtime/resources/error-patterns/external.edn` | Modified | Add 5 rate-limit patterns |
| `components/workflow/src/.../dag_resilience.clj` | **New** | Rate limit detection, failover, events |
| `components/workflow/src/.../dag_orchestrator.clj` | Modified | Wire resilience into loop, paused result, pre-completed-ids |
| `bases/cli/src/.../commands/resume.clj` | Modified | DAG checkpoint extraction, thread pre-completed-ids |
| `components/workflow/src/.../execution.clj` | Modified | Thread pre-completed-ids (1 line) |
| `components/workflow/src/.../configurable.clj` | Modified | Thread pre-completed-ids (1 line) |
| `bases/cli/src/.../commands/plan_executor.clj` | Modified | Thread pre-completed-ids (1 line) |

## Flow

```text
Task fails → "You've hit your limit · resets 2pm"
  ↓
execute-dag-loop: resilience/analyze-batch-for-rate-limits → detects rate limit
  ↓
resilience/handle-rate-limited-batch
  ↓
  ├─ allowed-failover-backends includes :openai → switch, re-queue, continue
  ├─ max-cost-per-workflow exceeded → pause
  ├─ allowed-failover-backends is empty → pause (default)
  └─ All allowed backends exhausted → emit :dag/paused, return {:paused? true}
                      ↓
                    User runs: miniforge run --resume <workflow-id>
                      ↓
                    resume.clj reads :dag/task-completed events
                      ↓
                    Passes pre-completed-ids into re-run
                      ↓
                    execute-dag-loop starts with pre-populated completed-ids
                      ↓
                    Skips already-completed tasks, runs only remaining
```

## Testing Plan

- [x] `bb test` — 277 tests, 1132 assertions, 0 failures
- [ ] Add test in `dag_resilience_test.clj` for `rate-limit-error?` and `analyze-batch-for-rate-limits`
- [ ] Add test in `dag_orchestrator_test.clj` for rate-limit-triggered pause
- [ ] Manual: run spec that triggers rate limits, verify failover or graceful pause
- [ ] Manual: `miniforge run --resume <paused-workflow-id>` resumes remaining tasks

## Related Issues/PRs

- Depends on: `refactor/phase-status-and-neutral-result` (base branch)
- Related: PR #233 (MCP artifact server integration)
- Future: per-backend cost/spend limits, provider-level rate limit increase options
