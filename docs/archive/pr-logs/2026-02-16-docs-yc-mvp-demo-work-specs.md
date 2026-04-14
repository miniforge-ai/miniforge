<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs(work): YC MVP demo script + execution work specs

## Layer

Documentation / Planning Inputs

## Depends on

- None

## Overview

Adds a YC-focused MVP demo script and two executable work specs so Miniforge
can autonomously finish MVP demo readiness with a deterministic, fast-path plan.

## Motivation

We need a single, compelling 8-10 minute YC demo narrative and a concrete
execution plan that Miniforge can run immediately. The deliverables in this PR
turn the high-level goal into runnable workflow and DAG inputs.

## What this adds

- New informative demo script:
  - `specs/informative/yc-mvp-demo-script.md`
- New sequential workflow spec:
  - `work/finish-yc-mvp.spec.edn`
- New parallel DAG task spec:
  - `work/finish-yc-mvp-tasks.edn`
- Updated index entry:
  - `specs/SPEC_INDEX.md` now links to the YC MVP demo script under
    "Demo Scripts"

## Changes in Detail

- `specs/informative/yc-mvp-demo-script.md`
  - Defines YC demo story arc, time-boxed script, success criteria, fallbacks,
    MVP scope boundary, and implementation tracks.
- `work/finish-yc-mvp.spec.edn`
  - Defines a dependency-ordered `:plan/tasks` workflow to finish MVP demo
    readiness end-to-end.
- `work/finish-yc-mvp-tasks.edn`
  - Defines a branchable DAG plan for parallel execution with task-level
    acceptance criteria and design rules.
- `specs/SPEC_INDEX.md`
  - Adds discoverability link to the new informative demo script.

## Testing Plan

- Pre-commit hooks executed on commit:
  - lint
  - markdown formatting/lint
  - `poly test` suite
  - GraalVM compatibility tests
- Result: passed in full during commit hook execution.

## Deployment Plan

- Merge to `main`.
- Execute one of:
  - `miniforge run work/finish-yc-mvp.spec.edn` (sequential)
  - `bb dogfood --dag work/finish-yc-mvp-tasks.edn` (parallel)

## Related Issues/PRs

- Source narrative:
  - `specs/informative/yc-mvp-demo-script.md`
- Branch:
  - `codex/yc-mvp-work-specs`

## Checklist

- [x] YC demo narrative captured in informative specs
- [x] Sequential execution spec added for workflow runner
- [x] Parallel DAG execution spec added for dogfooding
- [x] Spec index updated for discoverability
- [x] Pre-commit validation passed
