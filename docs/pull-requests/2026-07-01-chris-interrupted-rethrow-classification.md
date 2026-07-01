<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Interrupted Rethrow Classification

## Summary

Teach the exceptions-as-data scanner to classify simple rethrows inside
`InterruptedException` catch blocks as fatal-only cancellation propagation.

## Changes

- Carry minimal catch context while walking Clojure forms.
- Classify `(throw e)` as fatal-only only when the enclosing catch class is
  `InterruptedException`.
- Keep ordinary simple rethrows in non-boundary namespaces actionable.

## Verification

- Focused compliance-scanner tests.
- `clj-kondo` on changed scanner source and tests.
- Exception-as-data repo scan: cleanup-needed rows dropped from 45 to 41.
