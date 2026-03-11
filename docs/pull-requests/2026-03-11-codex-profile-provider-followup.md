# refactor: move state profile ownership to project-side providers

## Overview

Moves DAG state-profile ownership out of implicit kernel classpath overlays and onto explicit project-side providers.
The kernel now owns profile normalization and consumption; the workflow app layer owns the software-factory and ETL
profile resources and loads them intentionally.

## Motivation

The previous pass removed hardcoded profile literals from the kernel, but the resolution path still depended on
resource overlays on the active classpath. That kept project-specific configuration coupled to the kernel and blurred
the intended Polylith seam. This follow-up makes profile selection explicit at DAG construction time and fixes the last
few merge-centric assumptions that surfaced once the kernel default reverted to `:kernel`.

## Changes in Detail

- Added provider-aware profile resolution in `dag-executor`, with explicit provider injection on task/run/DAG creation.
- Kept the kernel default provider limited to the kernel-owned `:kernel` profile.
- Moved workflow-owned profile registry data under `components/workflow/resources/config/workflow/state-profiles/`.
- Added workflow-side provider loader helpers and exported them through the workflow interface.
- Threaded `:state-profile` and `:state-profile-provider` through `task-executor` so app code can choose profiles
  without pulling workflow internals into the kernel.
- Fixed generic DAG queries that still assumed `:merged` was the universal success state:
  `running-tasks` now respects each task profile, and `blocked-tasks` now keys off success-terminal states instead of
  `:run/merged`.
- Added regression coverage for:
  - explicit provider resolution
  - provider-backed DAG construction
  - workflow provider loading from resources
  - task-executor forwarding provider config
  - generic blocked/running task behavior after the kernel default changed

## Testing Plan

- `bb pre-commit`
- `clojure -M:dev:test -e "(require '[clojure.test :as t] 'ai.miniforge.dag-executor.state-test
  'ai.miniforge.workflow.state-profile-test 'ai.miniforge.task-executor.orchestrator-test) ..."`
- Attempted `bb test:integration`, but the repo-wide integration runner stalled idle without producing output or a
  failure signal during this pass

## Deployment Plan

Merge normally. This is an internal refactor plus test coverage.

## Related Issues/PRs

- Follow-up to PR #291
- Follow-up to PR #292
- Follow-up to PR #293

## Checklist

- [x] Keep kernel default profile ownership in the kernel only
- [x] Move workflow-owned profiles to workflow resources
- [x] Make profile selection explicit at DAG construction time
- [x] Remove remaining merge-only assumptions from generic DAG queries
- [x] Add regression coverage for the new provider seam
- [x] Re-run `bb pre-commit`
