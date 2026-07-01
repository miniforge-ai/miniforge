<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Failure Taxonomy Constructor Anomalies

## Summary

Return invalid constructor inputs from the failure taxonomy as anomaly
values instead of exceptions.

## Changes

- Convert taxonomy constructor validation helpers to return
  `:invalid-input` anomaly maps.
- Preserve valid constructor return shapes for classified failures and
  dependency attribution.
- Update classifier and anomaly-hint tests to assert returned anomaly data.

## Validation

- Direct failure-classifier classifier/anomaly-hint test namespaces
- Exceptions-as-data scanner target check for
  `components/failure-classifier/src/ai/miniforge/failure_classifier/taxonomy.clj`
