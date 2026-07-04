<!--
  Title: Boundary Wrapper Documentation
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Boundary Wrapper Documentation

Branch: `fix/boundary-wrapper-docs`

## Summary

This PR documents retained throwing wrappers that already have
data-returning paths underneath them:

- `workflow.runner-environment/acquire-execution-environment!` escalates
  governed acquisition failures for legacy runner callers, while
  `check-executor-for-mode` remains the anomaly-returning branch point.
- `bb-proc/run!` remains the bang/throwing wrapper, while `bb-proc/sh`
  returns the process result map for callers that branch on data.

No runtime behavior changes.

## Verification

- Raw scanner count:
  `{:cleanup-needed 19, :fatal-only 128, :local-boundary 22}`
