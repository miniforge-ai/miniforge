<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# fix/approval-handler-soft-dep-race

## Summary

Fixes a repo-green race in the web dashboard approval handlers that only showed
up under the full parallel `bb pre-commit` run.

The root problem was late `requiring-resolve` on the approval-handler response
and approval-manager path. Under the parallel test runner, those soft
dependency lookups could intermittently return `nil`, which then caused the
exception handler itself to throw a `NullPointerException` instead of returning
an anomaly response.

This PR removes that race from the approval-handler path by using normal
compiled dependencies for the response helpers and approval event-stream API.

## Key Changes

- adds direct `response` and `event-stream` requires in
  [handlers.clj](../../components/web-dashboard/src/ai/miniforge/web_dashboard/server/handlers.clj)
- replaces ad hoc `requiring-resolve` calls in the approval create/get/sign
  handlers with direct function calls in
  [handlers.clj](../../components/web-dashboard/src/ai/miniforge/web_dashboard/server/handlers.clj)
- keeps the small local anomaly helper functions, but makes them delegate to
  the directly required response interface in
  [handlers.clj](../../components/web-dashboard/src/ai/miniforge/web_dashboard/server/handlers.clj)

## Tests

- adds exception-path coverage for approval creation in
  [handlers_approval_test.clj](../../components/web-dashboard/test/ai/miniforge/web_dashboard/server/handlers_approval_test.clj)
- validates the full approval handler namespace under the JVM test runner
- validates the full repo hook path with `bb pre-commit`

## Validation

- `clj-kondo --lint` on touched files
- isolated JVM run of `ai.miniforge.web-dashboard.server.handlers-approval-test`
- `bb pre-commit`

## Outcome

The intermittent approval-handler NPE no longer reproduces under the full
parallel pre-commit run, and repo-green is restored for the next stacked PR.
