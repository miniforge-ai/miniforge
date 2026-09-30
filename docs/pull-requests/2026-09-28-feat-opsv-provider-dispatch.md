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
- Contain callback exceptions at a named boundary. Enforce one synchronous
  attempt on the calling thread, close escaped thunks after dispatch, and retain
  confirmed provider results. Other threads cannot invoke the provider operation.

Callback arity cannot be probed safely without running trusted code, and JVM
reflection is not portable to Babashka. Function shape is checked before the
read-only preflight; a wrong arity becomes `:invalid-input` at dispatch, before POST.

## Testing plan

All provider suites pass 19 tests and 116 assertions. The creation suite passes
seven tests and 27 assertions on JVM and Babashka using the built CLI plus provider
source paths. This prerequisite has no product consumer yet. Regressions cover GET/POST
ordering, refusal, successful dispatch, wrong arity, repeated invocation, escaped
thunks and preservation of a provider result when the callback throws afterward.

## Standards adversarial pass

No duplicated payload maps or new decision layer. Preserve the existing small
creation pipeline, closed argument validation and localized boundary diagnostics.

## Deployment plan

No production configuration changes. The callback is a trusted host extension,
not an untrusted input capability. Existing three-argument callers remain compatible.

## Related issues/PRs

N7 runtime PR execution; prerequisite extracted from #1935.

## Checklist

- [x] Provider suites and standards pass
- [ ] Final-head review settled and CI green
