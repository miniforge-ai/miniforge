<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Fix: Return data-plane HTTP failures as anomalies

## Overview

This PR removes normal-flow exception signaling from the `bb-data-plane-http`
component. Readiness polling and JSON HTTP helpers should return anomaly data
for expected process or HTTP failures instead of throwing inside the component.

## Motivation

`bb review` still reports cleanup-needed exceptions-as-data violations. The
data-plane HTTP component is a small primitive with injected HTTP/process test
surfaces, making it a focused cleanup wave before larger API migrations such as
`repo-dag`.

## Changes in Detail

- Convert startup readiness failures into `:fault` anomaly returns.
- Convert non-200 JSON HTTP responses into `:fault` anomaly returns.
- Update the public interface docs to describe anomaly results.
- Update component tests to assert anomaly values instead of thrown exceptions.

## Testing Plan

- Run the focused `bb-data-plane-http` component tests:
  `clojure -M:dev:test -e "(require 'ai.miniforge.bb-data-plane-http.core-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.bb-data-plane-http.core-test)"`.
  Latest result: 17 tests, 38 assertions, 0 failures.
- Run `bb review` and confirm the component no longer contributes
  cleanup-needed exception violations.
- Run `bb pre-commit` before opening the PR.

## Deployment Plan

No special deployment steps. This is a library behavior cleanup with no schema
or storage migration.

## Related Issues/PRs

- Follows PR #1276, which filtered fatal-only scanner rows from top-level
  `bb review` output.

## Checklist

- [x] Component implementation updated.
- [x] Tests updated and focused suite passes.
- [x] `bb review` count reduced.
- [x] `bb pre-commit` passes.
- [ ] PR opened, comments resolved, and CI green.
