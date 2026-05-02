## Summary

This PR hardens the dogfood phase boundaries that were allowing a failed DAG
task subworkflow to drift from `:implement` into `:verify` and `:review`, and
it tightens reviewer behavior so review fails closed instead of approving weak
or degraded handoffs.

## Problem

During live Claude dogfooding, a DAG task subworkflow showed three boundary failures:

- `:implement` failed, but the subworkflow still advanced
- `:review` could pass even with an explicit negative reviewer outcome
- nested task execution looked silent because DAG subworkflows were forced into quiet mode

That produced a misleading path where bad implement output reached review and
the operator could not reliably see nested task progress.

## Changes

- reviewer now parses usable review content even when the backend marks the
  response unsuccessful, and rejects when the output is unparseable
- reviewer adds an implementation handoff gate that rejects degraded curator
  recoveries and curator-reported scope deviations
- `:review-approved` now fails closed when there is an explicit negative review
  decision, even if legacy fallback approval flags are present
- implement curator recovery now normalizes recovered output to a successful
  handoff while preserving degraded provenance on the curated artifact
- implement preserves the curated artifact on the outer phase map so downstream
  review resolves the right handoff artifact
- implement, verify, and review no longer append failed phases to `:execution/phases-completed`
- DAG task subworkflows now inherit the parent `:quiet` setting instead of forcing nested execution to be silent

## Validation

- focused agent / gate / phase-software-factory / workflow regression tests
- live Claude dogfood confirmed review now fails and redirects back to
  `:implement` instead of slipping through to release

## Follow-up

The next dogfood run should verify the post-fix path end to end on both Claude
and Codex, with particular attention to nested output visibility and whether
any remaining implement-to-verify drift still exists elsewhere in the DAG path.
