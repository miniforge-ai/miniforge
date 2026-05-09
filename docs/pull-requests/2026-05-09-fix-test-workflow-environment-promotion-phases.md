# fix(test): isolate workflow environment promotion phases

## Summary

This PR makes environment-promotion workflow tests explicitly bind test phase
loader configuration so they remain valid under stable-derived project scope.

## Problem

`workflow.environment-promotion-integration-test` still depended on ambient
phase registrations that were only present when the wider repo classpath leaked
into the run.

## Changes

- update `workflow.environment-promotion-integration-test`
- bind explicit phase-loader test support for the promotion test family

## Validation

- `ai.miniforge.workflow.environment-promotion-integration-test`
- full `bb pre-commit` via the normal commit hook path
