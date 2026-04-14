<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix 8 Bugs Found During YC MVP Validation Run

**Branch:** `fix/yc-mvp-validation-bugs`
**Base:** `feat/meta-agent-supervision`
**Date:** 2026-03-03

## Summary

Fixes 8 bugs discovered by running `bb miniforge run work/finish-yc-mvp.spec.edn`
(8 DAG tasks) as a validation exercise. Result before fixes: 7 tasks completed,
2 failed, "Max iterations exceeded" from an infinite-loop failure cascade.

## Bugs Fixed

### Commit 1: DAG failure cascade + context forwarding

| Bug | Problem | Fix |
|-----|---------|-----|
| **4** — DAG infinite loop on failure | `compute-ready-tasks` only checks `completed-ids`, ignoring `failed-ids`. Tasks depending on failed tasks are perpetually "ready", causing the loop to hit iteration 100. | Added `failed-ids` parameter and `propagate-failures` for transitive failure marking. Tasks blocked by failures are skipped. |
| **3** — Sandbox context keys dropped | `task-sub-opts` drops `:executor`, `:environment-id`, `:sandbox-workdir`, `:worktree-path` from parent context. Sub-workflows can't use sandbox. | Forward all 4 keys from parent context. |
| **7** — Sub-workflow events orphaned | Each sub-workflow creates its own event files but parent DAG never tracks them. | Track sub-workflow IDs in `execute-dag-loop` and include `:sub-workflow-ids` in DAG result map. |

### Commit 2: Soft-failure retry (pre-existing)

| Bug | Problem | Fix |
|-----|---------|-----|
| **1** — No retry on soft agent failure | `leave-implement` mapped all `:error` agent status to `:failed` without checking retry budget. | Error + budget remaining → `:retrying`, error + budget exhausted → `:failed`. |

### Commit 3: Release + review phase repairs

| Bug | Problem | Fix |
|-----|---------|-----|
| **5** — Release phase 0ms no-op | `leave-release` always sets status to `:completed` regardless of result. | Check agent result `:status` flag. `:success` → `:completed`, `:error` → `:failed`/`:retrying`. |
| **6** — Review can't trigger repair loop | `leave-review` ignores `:review/decision` from agent output. `:changes-requested` silently marked `:completed`. | Check `[:output :review/decision]`. If `:changes-requested`, set `:retrying` with `:redirect-to :implement` and store review feedback. |

### Commit 4: Event file cleanup

| Bug | Problem | Fix |
|-----|---------|-----|
| **8** — Stale event file accumulation | `file-sink` creates per-workflow `.edn` files with no cleanup. | Added `cleanup-stale-events!` (configurable TTL, default 7 days). Called non-blocking on file-sink creation. |
| **2** — Broken generated test | `fleet_pure_test.clj` references non-existent var. | Already removed from repo (was untracked). |

## Files Changed

| File | Change |
|------|--------|
| `components/workflow/src/.../dag_orchestrator.clj` | Failure propagation, context forwarding, sub-workflow tracking |
| `components/phase/src/.../implement.clj` | Soft-failure retry logic |
| `components/phase/test/.../artifact_persistence_test.clj` | Test for retry behavior |
| `components/phase/src/.../release.clj` | Status-aware leave-release |
| `components/phase/src/.../review.clj` | Review decision → repair loop |
| `components/event-stream/src/.../sinks.clj` | Stale file cleanup |

## Verification

- `bb test` — 650 tests, 3181 assertions, 0 failures, 0 errors
- All pre-commit hooks pass (lint, format, test)
