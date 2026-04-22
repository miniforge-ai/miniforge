<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: restore dogfood and workflow-status CLI surfaces

## Overview

This PR repairs three stale CLI surfaces that were blocking the current
dogfooding loop:

- `bb miniforge ...` was missing the `workflow-resume` component on its
  Babashka classpath, so `run --resume` and `resume` blew up at load time.
- `bb dogfood:check`, `bb dogfood:dry-run`, and `bb dogfood` were still
  wired to an obsolete DAG helper path instead of the current
  `bb miniforge run <spec>` workflow.
- `bb miniforge status` still printed a TODO instead of reporting real
  workflow state from the local event log.

## Motivation

The immediate need is the dogfooding iteration on
`work/planner-convergence-and-artifact-submission.spec.edn`. During that
work we found that the documented commands no longer matched the current
repo. If we keep those commands in the surface area, they need to work
well enough to support the checkpoint/resume loop we use to avoid
replaying already-debugged phases.

## Layer

Adapter / CLI

## Base Branch

`main`

## Depends On

None.

## Changes In Detail

### Babashka CLI wiring

- Add `components/workflow-resume/src` to the `bb miniforge` task
  classpath so the resume command family loads in development mode.

### Dogfood tasks

- Replace the stale `dogfood-tasks.edn` / `scripts/dogfood-dag.clj`
  shell-out path with task functions that target the current spec-runner
  flow.
- Make `bb dogfood:check` accept either `GITHUB_TOKEN` or an authenticated
  `gh` session as valid GitHub auth.
- Default dogfood targeting to
  `work/planner-convergence-and-artifact-submission.spec.edn`, while still
  allowing an explicit spec path override.
- Make `bb dogfood:dry-run` print the exact command that would run.
- Make `bb dogfood` execute `bb miniforge run <spec>` directly with
  `GITHUB_TOKEN` injected from `gh auth token` when needed.

### Workflow status command

- Replace the TODO-only `status` implementation with a real event-log
  summary:
  - workflow status (`running` / `paused` / `failed` / `completed`)
  - recorded spec name
  - event count
  - last update timestamp
  - completed phases
  - completed DAG task count
- Add an all-workflows summary view for the no-arg `bb miniforge status`
  path.

## Standards Review

- Branch created from updated `main` in a dedicated worktree:
  `/private/tmp/mf-dogfood-cli-surface`
- PR scope kept to one concern: stale CLI surface repair for dogfooding
  and workflow inspection
- Code follows adapter-layer boundaries:
  - Babashka task wiring in `bb.edn`
  - task logic in `tasks/dogfood.clj`
  - CLI status adapter in `bases/cli/.../main.clj`
- No hook bypass planned; validation runs before commit

## Testing Plan

- [x] `bb miniforge help`
- [x] `bb miniforge status`
- [x] `bb miniforge status ad37c6d1-1ca5-444b-9397-11e69be21e25`
- [x] `bb dogfood:check work/planner-convergence-and-artifact-submission.spec.edn`
- [x] `bb dogfood:dry-run work/planner-convergence-and-artifact-submission.spec.edn`
- [x] `bb pre-commit`

Notes:

- `bb pre-commit` passed on the staged set.
- `bb lint:clj:all` still reports unrelated pre-existing repo issues outside
  this PR's scope, so the authoritative gate for this branch is the staged
  pre-commit run rather than repo-wide lint cleanliness.

## Deployment Plan

Merge normally. No config migration or rollout steps required.

## Related Issues/PRs

- Dogfooding follow-up after PR #634
- Target spec:
  `work/planner-convergence-and-artifact-submission.spec.edn`

## Checklist

- [x] Standards review completed
- [x] Validation commands completed
- [ ] Changes staged and committed
- [ ] PR opened for review
