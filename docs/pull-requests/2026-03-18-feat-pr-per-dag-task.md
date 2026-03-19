# feat: PR-per-DAG-task architecture

## Overview

Each DAG task now produces its own PR. After the DAG completes, PRs form a
dependency-ordered train matching the DAG topology. A monitoring phase watches
CI/review and runs fix loops. PRs merge in dependency order.

## Motivation

Previously, the DAG executor stripped `:release` from per-task sub-workflows.
After all tasks completed, the parent workflow skipped to `:done` — no PRs were
ever created for parallelized work. The release executor, PR lifecycle
controller, PR train manager, and fix loop infrastructure all existed but
weren't wired into the DAG flow.

This change wires the existing components together so the DAG path produces
observable, reviewable, mergeable PRs instead of silently completing.

## Changes in Detail

### Modified: `components/workflow/src/ai/miniforge/workflow/dag_orchestrator.clj`

- `task-sub-workflow`: No longer strips `:release` — sub-workflows include
  implement → verify → review → release → done
- `task-sub-input`: Includes `:task/id` for PR metadata
- `task-sub-opts`: Always sets `:create-pr? true`
- New `extract-pr-info-from-result`: Extracts PR info from release phase output
- `run-mini-workflow`: Attaches PR info to workflow result
- `workflow-result->dag-result`: Carries PR info through to DAG result
- `aggregate-results`: Collects `:pr-infos` vector across all tasks
- `finalize-dag`: Includes `:pr-infos` in terminal DAG result

### Modified: `components/workflow/src/ai/miniforge/workflow/configurable.clj`

- `execute-dag-for-plan`: Stores `:execution/dag-pr-infos` from DAG result
- `execute-phase-step`: After successful DAG, transitions to `:pr-monitor`
  instead of skipping to done or verify

### Modified: `components/workflow/src/ai/miniforge/workflow/context.clj`

- Reverted to clean state (flag propagation removed during simplification)

### Modified: `components/pr-train/src/ai/miniforge/pr_train/state.clj`

- New `link-prs-from-dag`: Links PR dependencies based on actual DAG topology
  rather than linear merge order. Diamond DAG (A→B, A→C, B+C→D) produces
  PR-D blocked by PR-B and PR-C.

### Modified: `components/pr-train/src/ai/miniforge/pr_train/core.clj`

- Added `link-prs-from-dag` to `PRTrainManager` protocol
- Implemented on `InMemoryPRTrainManager`

### Modified: `components/pr-train/src/ai/miniforge/pr_train/interface.clj`

- Exposed `link-prs-from-dag` in public API

### New: `components/workflow/src/ai/miniforge/workflow/dag_train.clj`

Assembles a PR train from DAG execution results.

| Function | Purpose |
|----------|---------|
| `build-dag-deps-map` | Extract task-id → #{dep-ids} from plan tasks |
| `build-task-to-pr-map` | Map task-id → pr-number from PR infos |
| `create-train-from-dag-result` | Create manager, add PRs, link via DAG topology |

### New: `components/workflow/src/ai/miniforge/workflow/dag_monitor.clj`

Monitors a PR train through CI, review, and merge.

| Function | Purpose |
|----------|---------|
| `create-pr-controllers` | Create a pr-lifecycle controller per PR |
| `monitor-pr-train` | Main loop: check train gate → attempt merges → continue monitoring |
| `monitor-dag-prs` | Top-level entry point |

### New: `components/phase-software-factory/src/ai/miniforge/phase/pr_monitor.clj`

Phase interceptor `:pr-monitor` wrapping train assembly + monitoring.

- Enter: reads `:execution/dag-pr-infos`, builds train, runs monitoring
- Leave: records merge results and duration
- Error: captures failure info

## Design Decisions

1. **Reuse over rebuild.** Release executor, PR lifecycle controller, and PR
   train manager all exist. New code is orchestration wiring only.

2. **DAG deps = PR deps.** Independent tasks produce independent PRs that can
   merge in any order. Diamond dependencies produce correct PR blocking.

3. **No config flag.** The DAG path is new — no existing behavior to preserve.
   Per-task release is unconditionally how the DAG works.

4. **Controller-per-PR.** Each PR gets its own `pr-lifecycle/controller`. The
   train provides merge-ordering coordination.

## Dependency Graph

```text
dag_orchestrator (keep :release, collect PR info)
       ↓
configurable (store pr-infos, route to :pr-monitor)
       ↓
dag_train (assemble train from DAG results)
       ↓
dag_monitor (monitor CI/review, merge in order)
       ↓
pr_monitor.clj (phase interceptor wrapping above)
```

`pr-train/state.clj` (DAG-aware linking) is orthogonal — used by dag_train.

## Testing

- All existing pr-train tests pass (12 test namespaces)
- All existing workflow tests pass (23 test namespaces)
- Pre-existing failure in `web_dashboard/views/fleet_view_gaps_test.clj` is unrelated
