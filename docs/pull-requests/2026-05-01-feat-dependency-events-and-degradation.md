# feat/dependency-events-degradation

## Summary

Adds the next implementation slice for external dependency health and failure
attribution by turning projected dependency health into typed events and wiring
those signals into degradation policy.

This PR stays focused on two things:

- emit canonical dependency health events from the reliability engine
- use dependency health projection, not raw exception strings, to drive
  degradation recommendations and transitions

It does **not** yet add CLI/dashboard rendering or workflow/evidence
attribution. Those remain the next slices.

## Why This Slice

The previous work can now classify failures correctly and project rolling
provider/platform health. What the runtime still lacked was:

- event-stream visibility when dependency health changes
- a canonical way for degradation policy to react to provider/platform state
- a reliable distinction between:
  - degraded dependency conditions
  - unavailable dependencies
  - operator-action-required dependency failures

Without this slice, dependency health existed in state but did not yet
participate in runtime control flow.

## Key Changes

- extended
  [defaults.edn](../../components/reliability/resources/config/reliability/defaults.edn)
  with a data-driven `:degradation-policy` for dependency status precedence,
  mode mapping, and FSM event mapping
- refactored
  [degradation.clj](../../components/reliability/src/ai/miniforge/reliability/degradation.clj)
  to:
  - compute degradation recommendations from both budgets and dependency health
  - centralize transition signal construction
  - move dependency-driven mode selection behind config instead of hardcoded
    branching
  - emit `:safe-mode/entered` for dependency-triggered safe-mode transitions
- extended
  [engine.clj](../../components/reliability/src/ai/miniforge/reliability/engine.clj)
  so `compute-cycle!`:
  - diffs prior and current dependency-health projections
  - emits `:dependency/health-updated`
  - emits `:dependency/recovered`
  - uses the same degradation recommendation helper as the manager
- added typed event constructors and exports in:
  - [core.clj](../../components/event-stream/src/ai/miniforge/event_stream/core.clj)
  - [interface/events.clj](../../components/event-stream/src/ai/miniforge/event_stream/interface/events.clj)
  - [interface.clj](../../components/event-stream/src/ai/miniforge/event_stream/interface.clj)
- added event schemas and registry entries in:
  - [schema.clj](../../components/event-stream/src/ai/miniforge/event_stream/schema.clj)
  - [event_type_registry.clj](../../components/event-stream/src/ai/miniforge/event_stream/event_type_registry.clj)
- localized the new event/degradation messages in:
  - [event-stream messages](../../components/event-stream/resources/config/event-stream/messages/en-US.edn)
  - [reliability messages](../../components/reliability/resources/config/reliability/messages/en-US.edn)
- added focused coverage in:
  - [engine_test.clj](../../components/reliability/test/ai/miniforge/reliability/engine_test.clj)
  - [degradation_test.clj](../../components/reliability/test/ai/miniforge/reliability/degradation_test.clj)
  - [core_test.clj](../../components/event-stream/test/ai/miniforge/event_stream/core_test.clj)
  - [interface_test.clj](../../components/event-stream/test/ai/miniforge/event_stream/interface_test.clj)

## Behavior Changes

- repeated dependency incidents now surface as explicit event-stream events
- dependency recovery now surfaces as a first-class event instead of only a
  state mutation
- a degraded dependency can move the system to `:degraded`
- unavailable or operator-action-required dependencies can move the system to
  `:safe-mode`
- degradation recommendations now come from one authority that considers both:
  - error budgets
  - dependency health projection

## What This PR Does Not Do

- no CLI or dashboard dependency attribution yet
- no workflow evidence or observe-phase attribution yet
- no generalized classification runtime extraction
- no new attention-card rendering yet

Those remain separate follow-up PRs.

## Validation

- `clj-kondo --lint` on touched reliability and event-stream files
- focused JVM tests for:
  - `ai.miniforge.reliability.degradation-test`
  - `ai.miniforge.reliability.engine-test`
  - `ai.miniforge.event-stream.core-test`
  - `ai.miniforge.event-stream.interface-test`
- full `bb pre-commit`
