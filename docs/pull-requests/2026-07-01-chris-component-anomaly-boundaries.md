<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Component Anomaly Boundary Classification

## Summary

Refine exception-as-data scanner classification for known boundary and
programmer-error cases, and remove an artificial workflow cleanup `ex-info`.

## Changes

- Treat `ai.miniforge.response.anomaly` as the canonical thrown-anomaly bridge.
- Classify `No matching ...` reflection guards and unmapped dispatch categories
  as fatal-only programmer errors.
- Read nested `:invalid-config` markers when classifying fail-fast config guards.
- Let event-stream knowledge failure constructors accept failure data maps, so
  workflow cleanup does not need to fabricate an exception.

## Verification

- Focused Clojure tests for compliance-scanner and event-stream constructors.
- `clj-kondo` on changed source and test files.
- Exception-as-data repo scan: cleanup-needed rows dropped from 51 to 45.
