# fix(test): isolate workflow runner phase loading

## Summary

This PR makes workflow runner test families explicitly bind their phase loader
configuration so they pass under project-scoped Polylith execution.

## Problem

Runner-oriented workflow tests were still resolving phases through ambient
loader configuration. That worked under the older wider classpath and failed
under narrowed stable-derived project runs.

## Changes

- update `workflow.runner-test`
- update `workflow.runner-extended-test`
- update `workflow.runner-iteration-test`
- update `workflow.run7-regression-test`
- all of the above now use explicit phase-loader fixtures instead of ambient
  product phase namespaces

## Validation

- `ai.miniforge.workflow.runner-test`
- `ai.miniforge.workflow.runner-extended-test`
- `ai.miniforge.workflow.runner-iteration-test`
- `ai.miniforge.workflow.run7-regression-test`
- full `bb pre-commit` via the normal commit hook path
