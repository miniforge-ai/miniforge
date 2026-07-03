<!--
  Title: Cleanup-Preserving Rethrow Classification
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Cleanup-Preserving Rethrow Classification

Branch: `fix/cleanup-rethrow-classification`

## Summary

The exceptions-as-data scanner counted cleanup-preserving rethrows as
actionable debt even when the catch body releases resources and then
rethrows the original failure.

This PR classifies the narrow same-binding rethrow pattern as
`:fatal-only` only when the catch body performs explicit cleanup before the
rethrow:

- scheduled executor shutdown via `.shutdownNow`
- future cancellation via `future-cancel`

Plain log-and-rethrow sites remain `:cleanup-needed`.

## Verification

- Focused scanner tests:
  `ai.miniforge.compliance-scanner.exceptions-as-data.fatal-only-classification-test`
- Raw scanner count:
  `{:cleanup-needed 24, :fatal-only 128, :local-boundary 17}`
