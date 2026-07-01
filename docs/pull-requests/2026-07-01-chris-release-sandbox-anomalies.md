<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Release Sandbox Path Anomalies

## Summary

Convert release sandbox path-safety rejections from exceptions into
structured shell-result failures.

## Changes

- Replace `assert-safe-container-path!` throwing behavior with
  `validate-safe-container-path`, which returns a failed shell result
  for traversal and shell-injection inputs.
- Return failed shell results from sandbox `write-file!` and `delete-file!`
  when paths are unsafe.
- Update release PR-doc steps to inspect sandbox write results and preserve
  their non-fatal degraded behavior without relying on exceptions.
- Update sandbox path-safety tests to assert failed result data.

## Validation

- Direct release-executor sandbox/core-pipeline test namespaces
- Exceptions-as-data scanner target check for
  `components/release-executor/src/ai/miniforge/release_executor/sandbox.clj`
