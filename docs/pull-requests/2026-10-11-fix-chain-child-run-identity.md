# fix: preallocate chain child-run identities

## Overview

Assign each started chain step its own workflow-run UUID before publication, binding, loading, or execution.
This closes the child-identity portion of N2.CH.1 without claiming full chain conformance.

## Motivation

Caller-supplied workflow IDs currently reach every child unchanged.
Early binding and loading failures also lack execution identity.
Both behaviors prevent reliable attribution of step outcomes.

## Changes in Detail

- Allocate child identity before the step-start event.
- Override a parent workflow ID while preserving shared correlation.
- Retain child identity in lifecycle events and early failure results.
- Supply the workflow reference during legacy envelope construction, preserving per-workflow sequence allocation.
- Reject returned execution identities that contradict the assigned child.

## Testing Plan

Exercise repeated invocations, parent ID overrides, early failures, and runner identity conflicts.
Run workflow consumer tests serially, normal commit hooks, and an adversarial standards pass.

Validation completed:

- Final workflow/event-stream suites passed across data-foundry, miniforge, miniforge-core, and miniforge-tui in 2m52s.
- Lint reported zero errors and warnings; the namespace-stratum checks passed.
- Constructor tests cover shared child counters and unchanged calls without a child reference.
- Rebuilt CLI: 39,125,420 bytes; isolated packaged regressions passed 14 tests and 97 assertions without production
  source overlays.

The adversarial pass caught sequence allocation before identity assignment.
The corrected constructors receive child identity before creating their envelopes.
Step lifecycle effects now occupy a separate namespace, keeping each implementation within three real strata.

## Deployment Plan

Ship through the existing workflow component.
Legacy chain event profiles remain legacy; this change adds explicit child-run references.
Chain-run snapshots, current-profile publication, edges, and recovery remain subsequent slices.

## Related Issues/PRs

Base branch: `main`.
Application-layer runtime identity fix, following merged #2016.
N1 §2.32 and N2 §14.4 require distinct child workflow identities.

## Checklist

- [x] Implementation and regression tests
- [ ] Serial consumer validation and normal hooks
- [x] Adversarial standards review
- [ ] Exact-head clean review and all CI checks before merge
