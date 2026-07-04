<!--
  Title: Scanner Gate Boundary Classification
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Scanner Gate Boundary Classification

Branch: `fix/scanner-gate-boundaries`

## Summary

This PR tightens exceptions-as-data scanner classification for two gate
patterns:

- `response/throw-anomaly!` inside `response/execute-with-handling` is a
  local response-chain boundary because the exception is captured into the
  returned chain.
- Localized `:behavioral/check-fn-not-function` guards are programmer-error
  checks and classify as `:fatal-only`.

## Verification

- Focused fatal-only scanner tests
- Focused local-boundary scanner tests
- Raw scanner count:
  `{:cleanup-needed 17, :fatal-only 129, :local-boundary 23}`
