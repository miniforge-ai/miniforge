<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Allow a trusted dispatch boundary after GitHub preflight

## Overview

Add an optional trusted dispatch callback to PR creation. Invoke it after the
head preflight and immediately before the one POST. Preserve the three-argument API.

## Motivation

Runtime authority and emergency stop must be checked after a potentially slow GET.
This small provider prerequisite is extracted from #1935 to keep that application
PR within its review budget as cleanup regressions are added.

## Layer and dependencies

Provider adapter extension only. Application policy remains outside this component.
The trusted callback receives an operation thunk and must call it at most once.

## Changes in detail

- Validate the optional callback at the public interface.
- Invoke it only after a successful exact-head preflight.
- Return its refusal without provider mutation; retain direct dispatch by default.

## Testing plan

Run the provider suites, scoped standards, hooks and packaged compatibility checks.
The regression proves GET occurs, refusal prevents POST, and invalid callbacks fail.

## Standards adversarial pass

No duplicated payload maps or new decision layer. Preserve the existing small
creation pipeline, closed argument validation and localized boundary diagnostics.

## Deployment plan

No production configuration changes. The callback is a trusted host extension,
not an untrusted input capability. Existing three-argument callers remain compatible.

## Related issues/PRs

N7 runtime PR execution; prerequisite extracted from #1935.

## Checklist

- [ ] Provider suites and standards pass
- [ ] Final-head review settled and CI green
