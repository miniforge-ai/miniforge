<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Progress Detector Tool Profile Anomalies

## Summary

Convert progress-detector tool profile registration validation from thrown
response anomalies to canonical anomaly data.

## Changes

- Return `:invalid-input` anomaly maps when `register!` receives a profile
  missing `:tool/id` or failing the `ToolProfile` schema.
- Preserve successful registration behavior: valid profiles still return the
  updated registry map.
- Update public interface documentation and regression tests to assert the
  data-returning contract.

## Validation

- `clojure -M:dev:test -e '(require ...progress-detector tests...) ...'`
- `clj-kondo --lint` on changed progress-detector files
- `bb pre-commit`
