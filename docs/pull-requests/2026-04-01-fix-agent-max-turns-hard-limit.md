# fix: raise planner and implementer max-turn hard limits

## Overview

Raises the planner and implementer MCP turn budgets from 25 to 40 and adds
explicit "stop exploring and submit" instructions to both prompts.

## Motivation

The current 25-turn ceiling is too tight for artifact-backed planning and
implementation flows. Agents can exhaust their turn budget while still
exploring context, which prevents them from reaching the required MCP submit
tool call and leaves phases unfinished.

## Changes in Detail

- `components/agent/resources/prompts/implementer.edn`
  - raises `:prompt/max-turns` from `25` to `40`
  - adds a hard-limit section telling the implementer to stop after 5 file reads
    and reserve the last turns for artifact submission
- `components/agent/resources/prompts/planner.edn`
  - raises `:prompt/max-turns` from `25` to `40`
  - adds the same hard-limit framing for planning, with a 15-file-read cutoff
- `components/agent/src/ai/miniforge/agent/planner.clj`
  - updates the planner MCP invocation options to pass `:max-turns 40`

## Testing Plan

- [ ] Run targeted planner/implementer agent tests
- [ ] Dogfood a planning + implementation cycle to confirm agents submit before
  the new hard limit
- [ ] Run `bb pre-commit`

## Deployment Plan

Merge as a prompt/configuration fix. The behavior change is limited to planner
and implementer turn budgeting and should take effect on the next workflow run.

## Related Issues/PRs

- None linked in this branch yet

## Checklist

- [x] Added a PR doc under `docs/pull-requests/`
- [x] Documented the prompt and runtime turn-budget changes
- [ ] Captured targeted validation results in this doc before merge
