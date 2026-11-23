<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: enforce explicit required chain bindings

## Overview

Add opt-in required bindings without changing deployed shorthand behavior.

## Motivation

N2 §14.2 requires unresolved required bindings to fail before dependent execution. Existing declarations
have no required flag, so silently changing all shorthand bindings would break their established optional semantics.

## Changes in Detail

Definition-time descriptors wrap existing predecessor vector paths:

```clojure
{:step/input-bindings
 {:artifact {:binding/expression [:prev/artifacts]
             :binding/required? true}}}
```

This is an implementation encoding for definitions, not a replacement for N2's runtime binding records.
Runtime records retain the required flag, resolved expression/value, and actual upstream UUID when an edge exists.
Future child UUIDs are not supplied by definition authors. Existing string, keyword, and vector shorthand stays optional.
An explicit descriptor requires both fields and rejects unknown fields. Nested descriptors are not supported.
Descriptors cover predecessor dependencies governed by N2 §14.2. Chain-input requirements remain the child workflow's
input contract.
Missing required values stop child loading and fail the step, edge, and chain. An absent predecessor never invents an
edge UUID.
Failed resolution retains redacted binding records without copying raw execution inputs into diagnostics.
Edge terminal publication failures preserve those records and the original publication failure.

## Testing Plan

Verify declaration admission, detached snapshots, false versus missing values, and required failure before child loading.
Exercise edge failure evidence, unchanged optional behavior, serial consumers, and isolated packaged execution.

All three workflow/evidence consumers passed in 1m31s. Real-storage JVM suites passed 18 tests and 143 assertions.
The rebuilt CLI (39,148,319 bytes) passed 51 tests and 376 assertions from an isolated directory without source overlays.
The pinned standards scan found zero violations across 4,388 files. Lint and namespace-strata checks passed.

The adversarial pass checked descriptor admission, immutable snapshot copying, optional compatibility, and
false-versus-missing semantics.
Resolution, edge construction, and error conversion reuse shared helpers rather than duplicating maps or redaction policy.
Regressions cover secret-bearing partial results, publication failure, actual child UUIDs, and both current and legacy
execution modes.

## Deployment Plan

Required binding failure is an execution result, not permission to retry or recover. Durable result retention,
reconstruction, and recovery remain separate work. No production provider effects are needed for validation.

## Related Issues/PRs

Base: clean main at merged #2032 (`2679ea4ca`).
Layer: binding domain policy and its admission/execution boundaries.

## Checklist

- [x] Declaration and required-resolution policy
- [x] Boundary, evidence, and execution regressions
- [x] Serial consumers, package checks, and adversarial standards review
- [ ] Normal signed hooks, exact-head clean review, and all CI before merge
