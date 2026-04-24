# feat: formalize PR lifecycle controller FSM

## Overview

Extract the PR lifecycle controller status contract into a dedicated FSM
module and wire the controller through that contract for status validation and
transition execution.

## Motivation

The controller already behaved like a state machine, but the transition policy
was embedded in controller code. This slice makes the lifecycle contract
explicit, testable, and reusable through the shared FSM foundation.

## Changes In Detail

- Add `pr_lifecycle.fsm` with:
  - controller status order and transition graph loaded from
    `config/pr-lifecycle/fsm.edn`
  - valid-status predicates and valid-target helpers
  - explicit transition execution with structured failure maps
  - compiled controller machine config backed by `fsm/define-machine`
- Refactor `pr_lifecycle.controller` to route `update-status!` through the FSM
  and preserve the existing exception/reporting contract
- Load controller defaults from
  `config/pr-lifecycle/controller-defaults.edn`
- Localize controller/FSM emitted text through
  `config/pr-lifecycle/messages/en-US.edn`
- Update controller tests to assert rejected invalid transitions, terminal
  behavior, and idempotent same-state updates
- Add focused FSM tests for the happy path, fix loop, invalid inputs, terminal
  states, and valid-target predicates

## Testing Plan

- `clojure -M:test -e '(require ... ) (clojure.test/run-tests ...)'` for the
  `pr-lifecycle.fsm-test` and `pr-lifecycle.controller-test` namespaces
- `clj-kondo --lint` on the touched `pr-lifecycle` source and test files

## Checklist

- [x] PR lifecycle status transitions are FSM-backed
- [x] Invalid transitions are rejected with structured errors
- [x] Tests updated alongside the controller change
- [x] Targeted lint passes
- [x] Targeted namespace tests pass
