# fix(test): add explicit workflow phase test support

## Summary

This PR adds workflow-specific phase test support and removes the remaining
ambient chain/phase resource assumptions from the workflow loader boundary.

## Problem

Workflow tests were relying on chain and phase resources being present on the
full repo classpath. The restored stable-derived project runs narrowed the
classpath and exposed those assumptions.

## Changes

- add `workflow/phase_test_support.clj` for synthetic workflow test phases
- update `workflow.chain-loader-test` to use explicit test support instead of
  ambient resources

## Validation

- `ai.miniforge.workflow.chain-loader-test`
- full `bb pre-commit` via the normal commit hook path
