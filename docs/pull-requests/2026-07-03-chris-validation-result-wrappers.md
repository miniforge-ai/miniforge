<!--
  Title: Validation Result Wrappers
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Validation Result Wrappers

Branch: `fix/validation-result-wrappers`

## Summary

This PR adds anomaly-returning validation APIs for two remaining validation
helpers while preserving their legacy throwing behavior as documented local
boundary wrappers.

- Add `decision/validate-result` and re-export it through the decision
  interface.
- Add `control-plane/validate-transition-result` and re-export it through the
  control-plane interface.
- Keep `decision/validate` and `state-machine/validate-transition` as
  compatibility wrappers around their canonical anomaly-returning helpers.

## Verification

- Focused decision interface tests
- Focused control-plane interface tests
- Raw scanner count:
  `{:cleanup-needed 22, :fatal-only 128, :local-boundary 19}`
