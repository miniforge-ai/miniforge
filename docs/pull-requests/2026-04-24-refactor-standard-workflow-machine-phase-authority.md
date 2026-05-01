<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: make standard workflow phase selection machine-driven

## Overview

Remove the remaining standard-runner dependence on `:execution/phase-index` as
the authority for phase selection and retry behavior.

The compiled workflow execution machine already owns the active phase. This PR
makes the standard interceptor runner read that authority directly from the
machine snapshot instead of selecting interceptors from a mutable phase index in
context.

## Motivation

The workflow FSM work already made the execution machine authoritative for
status and phase progression, but the standard runner still had two lingering
control paths:

- `workflow.execution/execute-phase-step` selected the current interceptor from
  `:execution/phase-index`
- `workflow.runner/apply-backoff-if-retrying!` inferred retries by comparing
  pre/post `:execution/phase-index`

That meant the standard path still relied on an index projection as a live
control input instead of treating it as derived state.

## Changes In Detail

- add `workflow.fsm/machine-active-phase-entry` so the runner can ask the
  compiled machine for the active phase entry directly
- update `workflow.execution/execute-phase-step` to select the interceptor from
  the machine snapshot, with `:execution/current-phase` only as a fallback for
  non-machine test contexts
- stop using `:execution/phase-index` as the retry signal in
  `workflow.runner/apply-backoff-if-retrying!`
- add tests proving:
  - the compiled machine exposes the active phase entry
  - tampering with `:execution/phase-index` does not break the standard runner

## Scope

This PR is intentionally limited to the standard workflow runner.

It does **not** yet convert the configurable workflow path in
`workflow.configurable`, which still uses ad hoc `select-next-phase` logic and
needs its own compiler-backed follow-up.

## Testing Plan

- `clj-kondo --lint` on the touched workflow namespaces and tests
- `bb test components/workflow`
- `bb pre-commit`

## Checklist

- [x] Standard runner phase selection reads from machine state
- [x] `:execution/phase-index` is no longer standard-runner phase authority
- [x] Retry backoff no longer depends on phase-index comparison
- [x] Tests cover machine-backed phase selection
