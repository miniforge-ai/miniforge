<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# feat: add FSM reachability guards for compiled workflows

## Overview

Strengthen workflow FSM validation in two places:

- test coverage now asserts exact reachable and unreachable state sets for the
  compiled workflow execution machine
- workflow registration now rejects compiled workflows whose state graph
  contains unreachable phases or unreachable machine states

This moves reachability from an informal expectation into both the test suite
and the workflow registration boundary.

## Motivation

The existing FSM tests covered selected happy-path transitions, but they did
not prove the stronger invariant:

- states we expect to be reachable are reachable
- states we do not expect to be reachable are not reachable

The registry also accepted workflows without validating the compiled execution
machine. That meant a malformed workflow could be discovered and registered even
if its machine graph contained unreachable states.

## Changes In Detail

- add compiled execution-machine graph metadata in `workflow.fsm`
- add reachability helpers:
  - `state-graph`
  - `compiled-state-ids`
  - `reachable-states`
  - `unreachable-states`
- tighten redirect transitions in the compiled machine so they only target the
  current phase's configured `:on-fail` target instead of allowing redirect
  events to every phase from every phase
- extend `validate-execution-machine` to reject:
  - unreachable compiled phase states
  - unreachable machine states
- update `workflow.registry/register-workflow!` to validate workflows at
  registration time and reject invalid compiled machines
- localize the new validation messages in `en-US.edn`

## Testing

- add exact reachability / negative-reachability assertions in
  `workflow.fsm-test`
- add registration-boundary coverage in `workflow.registry-test`
- existing workflow shard remains green after tightening redirect legality

## Boundary Behavior

After this PR, workflows are rejected at registration when:

- schema / DAG validation fails
- compiled execution-machine targets are unresolved
- compiled execution-machine phases are unreachable
- compiled execution-machine states are unreachable

That means workflow discovery and registration now enforce the FSM contract
instead of relying on runtime execution to surface graph defects.

## Testing Plan

- `clj-kondo --lint` on touched workflow namespaces and tests
- `bb test components/workflow`
- `bb pre-commit`

## Checklist

- [x] Compiled workflow machine exposes a reachability graph
- [x] Tests assert exact reachable and unreachable state sets
- [x] Negative reachability is covered
- [x] Invalid compiled workflows are rejected at registration
