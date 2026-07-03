<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Loop Transition Anomaly Result

Branch: `fix/loop-transition-anomaly`

## Summary

`loop.inner/transition` threw directly on invalid FSM transitions. This PR adds
`transition-result` as the canonical anomaly-returning sibling and keeps
`transition` as the documented compatibility wrapper for existing callers.

## Verification

- `clj-kondo` on touched loop source and test
- focused `loop.inner-test`
- raw exceptions-as-data scan:
  `{:cleanup-needed 26, :fatal-only 126, :local-boundary 17}`
