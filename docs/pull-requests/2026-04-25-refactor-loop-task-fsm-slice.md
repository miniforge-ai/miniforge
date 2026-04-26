# refactor: move loop and task lifecycle slices toward shared FSMs

## Overview

Replace the hand-maintained loop/task lifecycle maps with shared `components/fsm`
machine definitions, while keeping the public loop/task APIs stable for this
slice.

## Motivation

The loop inner lifecycle and task lifecycle both duplicated transition authority
as ad hoc maps and validation helpers. That made it easy for the transition
tables, terminal-state checks, and tests to drift apart from the shared FSM
direction already used elsewhere in the repo.

This slice narrows that gap without expanding scope into orchestration or
workflow runtime changes.

## Changes In Detail

- Define a shared-FSM-backed inner loop lifecycle machine in
  `components/loop/src/ai/miniforge/loop/inner.clj`
- Define a shared-FSM-backed task lifecycle machine in
  `components/task/src/ai/miniforge/task/core.clj`
- Derive transition maps and transition-event lookups from the machine
  definitions instead of maintaining separate hand-written transition tables
- Keep the existing public helpers such as `valid-transition?`,
  `validate-transition`, `terminal-state?`, and `transition`
- Extract small result/metric helpers to reduce duplicated lifecycle map
  construction
- Add reachability and final-state coverage in loop/task tests

## Testing Plan

- Run `clj-kondo` on the edited loop/task source and test files
- Run `bb test components/loop components/task`

## Deployment Plan

No deployment changes. This is an internal refactor and test coverage update.

## Related Issues/PRs

- Follows the workflow FSM migration work already merged
- Keeps the loop/task slice disjoint from concurrent workflow/supervision work

## Checklist

- [x] Shared FSM definitions added for loop/task lifecycle slices
- [x] Hand-maintained transition authority removed from owned files
- [x] Reachability and invalid-transition coverage added
- [x] PR write set kept within owned files, related tests, and this doc
