<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR Responder Expected Anomalies

## Summary

Remove exception-driven expected anomaly handling from the PR comment
responder orchestration path.

## Changes

- Return invalid-URL and fetch-failure anomalies from `respond-to-comments!`
  instead of rethrowing them through response exceptions.
- Remove the private throwing fetch boundary and route orchestration through
  the anomaly-returning fetch helper.
- Make the CLI `pr respond` command explicitly fail on returned anomalies.
- Update responder tests to assert anomaly values and fetch-failure short
  circuiting.

## Validation

- `clojure -M:poly test brick:pr-lifecycle`
- `clojure -M:poly test brick:cli`
- Exceptions-as-data scanner target check for
  `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/responder.clj`
