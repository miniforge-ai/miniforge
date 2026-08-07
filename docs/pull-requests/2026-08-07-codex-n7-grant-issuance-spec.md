# docs: reconcile N7 grant issuance work contracts

## Overview

Reconciles the active Ariadne execution-grant work specs with the N7 contract
and the implementation already merged to `main`. It makes issuance policy
decisions explicit and separates merge and deployment enforcement into
independently reviewable work.

## Motivation

The active queue still includes completed scope-enforcement work, while the
issuance and effect-call-site specs leave key policy decisions open and combine
multiple implementation strata. Those gaps can cause autonomous workflows to
repeat completed work or produce oversized, poorly layered changes.

## Changes in Detail

- Archive the completed grant-scope work spec.
- Correct stale revocation and issuance acceptance criteria.
- Record explicit issuance site, lifetime, ceiling, scope, and delegation
  decisions derived from N7.
- Split merge and deployment grant enforcement into separate work specs.
- Regenerate the work queue from the reconciled contracts.

## Standards Gap Analysis

- Removed completed scope enforcement from the active queue.
- Removed an undelivered issuance claim from the completed revocation contract.
- Replaced open issuance questions with explicit runtime ownership, effect
  policy, scope, lifetime, ceiling, and delegation decisions.
- Replaced the combined merge/deploy contract with one component-scoped spec
  per effect path.
- Added a prerequisite transaction-fencing contract after adversarial review
  showed that max-count alone cannot prevent replay or concurrent double commit.
- Required shared policy/fixture constructors, public boundary validation,
  small named functions, namespace decomposition, and three-layer stratified
  design in the implementation contracts.
- Updated completed dependency metadata that still named the retired combined
  call-site spec.

## Testing Plan

- `bb work:queue` regenerated the active queue; all four changed active specs
  parse, contain every required field, and reference a known theme.
- Every commit passed `bb pre-commit`, including changed-file kondo at zero
  errors and warnings, formatting, smoke tests, and GraalVM checks.
- `bb test:poly` passed the full five-project suite in 6 minutes 13 seconds.
- `bb pr-budget` reports 362 of 600 reportable lines.
- The complete diff was reviewed adversarially against specification,
  PR-layering, function/component design, and work-spec-authoring standards.

## Deployment Plan

Merge to `main`. This documentation-only change affects future work planning;
it does not change runtime behavior.

## Related Issues/PRs

- N7 Operational Policy Synthesis and Verification
- PR #1688 — grant scope enforcement
- PR #1696 — execution-grant delegation boundaries

## Checklist

- [x] Active specs match delivered behavior and remaining N7 work
- [x] Issuance decisions are explicit and testable
- [x] Merge and deployment enforcement are independently reviewable
- [x] Work queue is regenerated
- [x] Repository checks and adversarial review pass
