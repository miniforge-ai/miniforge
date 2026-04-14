<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Polymorphic `miniforge run` + Resume + Work File Conversion

**Branch**: `feat/polymorphic-run-resume`
**Date**: 2026-03-04
**Status**: Open

## Overview

Extends `miniforge run` to accept DAG/plan files (not just specs),
adds `--resume` to recover interrupted workflows, and converts
remaining work files to proper `.spec.edn` format.

## Motivation

Dogfooding exhausted all `.spec.edn` files. The remaining 14 work
files were either DAG task format or had wrong extensions. Plan-only
workflows generated plans that couldn't be resumed, and rate limits
or failures couldn't be recovered from. This PR addresses all three gaps.

## Changes in Detail

### New Files

| File | Purpose |
|------|---------|
| `bases/cli/src/ai/miniforge/cli/main/commands/plan_executor.clj` | Normalizes DAG/plan formats and executes via `dag-orchestrator/execute-plan-as-dag` |
| `bases/cli/src/ai/miniforge/cli/main/commands/resume.clj` | Reconstructs workflow state from event files, resumes from last completed phase |
| `docs/pull-requests/2026-03-04-feat-polymorphic-run-resume.md` | This PR doc |

### Modified Files

| File | Changes |
|------|---------|
| `bases/cli/src/ai/miniforge/cli/main/commands/run.clj` | Polymorphic dispatch: parses input, detects type (spec/DAG/plan), routes to correct executor. Added `--resume` routing. |
| `bases/cli/src/ai/miniforge/cli/main.clj` | Added `:resume {:alias :r}` to run command spec; updated help text |

### Work File Changes

| Action | Files | Count |
|--------|-------|-------|
| Renamed `.edn` → `.spec.edn` | agent-failure-classification, codex-cli-integration, dashboard-production-ready, finish-event-telemetry, intelligent-workflow-selection, llm-workflow-recommendation, refactor-error-classifier, resolve-pr-conversations, self-healing-workarounds, web-dashboard, workflow-redesign-use-case-targeted | 11 |
| Converted DAG → spec | finish-yc-mvp-tasks, gitlab-support-tasks | 2 |
| Rewritten (was DAG despite .spec.edn extension) | gitlab-support.spec.edn | 1 |
| Kept as-is (DAG format, for testing) | dogfood-tasks.edn | 1 |

## Architecture

### Input Type Detection (`run.clj`)

```text
parsed file
  ├─ has :spec/title  → existing spec workflow path
  ├─ has :dag-id      → plan_executor (normalize DAG → plan, execute)
  ├─ has :plan/id     → plan_executor (execute directly)
  └─ else             → error: unrecognized format
```

### Plan Executor (`plan_executor.clj`)

1. Normalizes DAG format to plan format:
   - String `:task/id` → deterministic UUID via `UUID/nameUUIDFromBytes`
   - `:task/deps` → `:task/dependencies`
   - `:description` → `:task/description`
   - `:acceptance-criteria` string → vector
2. Sets up execution context (LLM client, event stream, control state)
3. Calls `dag-orchestrator/execute-plan-as-dag` with implement → verify → done pipeline

### Resume (`resume.clj`)

1. Reads event file from `~/.miniforge/events/<workflow-id>.edn`
2. Extracts completed phases from `:workflow/phase-completed` events
3. Trims the workflow pipeline to remove completed phases
4. Re-runs `run-pipeline` with the trimmed pipeline (no modifications to workflow component needed)

## Testing

- **650 tests, 0 failures, 0 errors** — all existing tests pass
- Manual verification targets:
  - `bb miniforge run work/dogfood-tasks.edn` — detects DAG format, executes plan directly
  - `bb miniforge run work/agent-failure-classification.spec.edn` — parses as spec (existing path)
  - `bb miniforge run --resume <workflow-id-from-event-file>` — resumes from checkpoint

## Stratum

- **Stratum 3 (CLI commands)**: plan_executor.clj, resume.clj, run.clj modifications
- **Stratum 4 (CLI entry point)**: main.clj dispatch table update
- **Stratum 0 (Data)**: Work file format conversions
