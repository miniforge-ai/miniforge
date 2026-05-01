# feat/dependency-health-projection

## Summary

Adds the next implementation slice for external dependency health and failure
attribution by modeling rolling dependency health separately from workflow
execution state.

This PR keeps the scope narrow:

- adds config-driven dependency-health thresholds and status mapping
- introduces a pure dependency-health projector in `reliability`
- extends the reliability engine state to carry dependency-health projections
- adds dependency-health entities to `supervisory-state`
- wires supervisory-state to store projected dependency health from canonical
  dependency events
- adds focused tests for projection, engine integration, and accumulator
  behavior

It does **not** yet add typed dependency-health event constructors or connect
dependency health to degradation policy transitions. Those are the next slice.

## Why This Slice

The dependency-attribution taxonomy and classifier work can now say whether a
failure came from:

- Miniforge
- user environment/setup
- an external provider
- an external platform

What the system still lacked was a canonical, rolling health model that can
answer:

- is Anthropic degraded or unavailable right now?
- is GitHub unavailable or just rate-limited?
- is the local user environment misconfigured?
- does this require retries, operator action, or neither?

That health state belongs in the reliability/supervisory layer, not inside
workflow phases.

## Key Changes

- extended
  [defaults.edn](../../components/reliability/resources/config/reliability/defaults.edn)
  with dependency-health config:
  - window size
  - status precedence
  - status thresholds
  - dependency-class to health-status mapping
- added the pure projector in
  [dependency_health.clj](../../components/reliability/src/ai/miniforge/reliability/dependency_health.clj)
- extended
  [engine.clj](../../components/reliability/src/ai/miniforge/reliability/engine.clj)
  so `compute-cycle!` accepts:
  - `:dependency/incidents`
  - `:dependency/recoveries`
  and returns/stores `:dependency-health`
- exported the new projection API and schemas from
  [interface.clj](../../components/reliability/src/ai/miniforge/reliability/interface.clj)
  and
  [schema.clj](../../components/reliability/src/ai/miniforge/reliability/schema.clj)
- added a new supervisory dependency entity in
  [schema.clj](../../components/supervisory-state/src/ai/miniforge/supervisory_state/schema.clj)
- updated
  [accumulator.clj](../../components/supervisory-state/src/ai/miniforge/supervisory_state/accumulator.clj)
  to accept:
  - `:dependency/health-updated`
  - `:dependency/recovered`
  - `:supervisory/dependency-upserted`
- added focused coverage in:
  - [dependency_health_test.clj](../../components/reliability/test/ai/miniforge/reliability/dependency_health_test.clj)
  - [sli_test.clj](../../components/reliability/test/ai/miniforge/reliability/sli_test.clj)
  - [accumulator_test.clj](../../components/supervisory-state/test/ai/miniforge/supervisory_state/accumulator_test.clj)

## What This PR Does Not Do

- no event-stream schema or constructor additions yet
- no degradation-policy changes yet
- no attention derivation changes yet
- no CLI or dashboard attribution yet
- no workflow/evidence integration yet

Those are intentionally left for the next PRs in the stack.

## Validation

- `clj-kondo --lint` on touched reliability and supervisory-state files
- targeted JVM tests for:
  - `ai.miniforge.reliability.dependency-health-test`
  - `ai.miniforge.reliability.sli-test`
  - `ai.miniforge.reliability.degradation-test`
  - `ai.miniforge.supervisory-state.accumulator-test`
- full `bb pre-commit`
