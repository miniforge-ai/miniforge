<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
  Licensed under Apache 2.0.
-->

# feat: formalize workflow agent default contract

## Overview
Formalizes the workflow `:phase/agent` contract so `nil` is no longer
used as a meaningful value in shipped workflow defaults.

This slice introduces an explicit `:default` contract for
handler/interceptor-owned phases and keeps `:none` as the only value
that means "this phase has no agent."

## Motivation
Using `nil` as a meaningful workflow enum value makes the execution
contract ambiguous:

- `nil` should mean absence or unspecified
- `:none` should mean an intentional no-agent phase
- handler/interceptor-owned phases should be explicit instead of relying
  on `nil`

The Observe phase shipped with `:agent nil` in its defaults, which made
the data contract weaker than the execution model. This PR makes that
contract explicit before downstream product workflows depend on it.

## Base Branch
`main`

## Changes In Detail
- Changes `components/workflow/resources/config/workflow/observe-phase.edn`
  from `:agent nil` to `:agent :default`
- Updates `agent_factory.clj` so `:default` is treated as a
  handler/interceptor-owned contract and throws if it reaches raw agent
  construction
- Expands `agent_factory_test.clj` with a direct `:default` failure-path
  test
- Expands `observe_phase_test.clj` to assert the shipped default config
  uses `:default`

## Testing Plan
Executed in `/tmp/cc-miniforge-agent-default`:

```sh
git diff --check
clojure -M:test -e "(require 'ai.miniforge.workflow.agent-factory-test 'ai.miniforge.workflow.observe-phase-test) (let [result (clojure.test/run-tests 'ai.miniforge.workflow.agent-factory-test 'ai.miniforge.workflow.observe-phase-test)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))"
```

Result:
- `20` tests
- `42` assertions
- `0` failures
- `0` errors

## Deployment Plan
No migration step is required.

Downstream workflow packs can now normalize on:
- `:none` for true no-agent phases
- `:default` for handler/interceptor-owned phases
- missing `:agent` for unspecified/absent

## Architecture Notes
- This is an execution-contract cleanup, not a behavior expansion.
- It intentionally fails loud if `:default` is routed into raw agent
  creation without a handler or interceptor. That preserves the semantic
  distinction from `:none`.

## Related PRs
- `thesium-workflows` follow-on to replace private workflow `nil` agent
  values with `:none`

## Checklist
- [x] Removed shipped `:agent nil` usage from Miniforge workflow defaults
- [x] Added explicit `:default` semantics at the runtime boundary
- [x] Added regression tests for the contract
