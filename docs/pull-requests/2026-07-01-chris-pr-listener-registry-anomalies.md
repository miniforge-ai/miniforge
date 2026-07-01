<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR Listener Registry Expected Anomalies

## Summary

Remove the remaining exception-driven expected anomaly paths from the
PR listener registry.

## Changes

- Return typed DAG errors directly for listener entry validation failures.
- Return typed read-failed results for registry files with trailing EDN forms.
- Return typed write-failed results for atomic move failures after temporary-file cleanup.

## Validation

- `clojure -M:poly test brick:pr-lifecycle`
- Exceptions-as-data scanner target check for
  `components/pr-lifecycle/src/ai/miniforge/pr_lifecycle/listener_registry.clj`
