<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# feat: make the workflow execution machine authoritative

## Overview

Replace the workflow runner's split transition authority with a compiled
per-run execution machine. Phase execution still uses the existing
interceptor enter/leave/error lifecycle, but phase advancement now flows
through machine events instead of direct phase-index arithmetic.

## Motivation

The repo already had a coarse workflow FSM, but real workflow progression was
still controlled by:

- `:execution/phase-index`
- `:execution/current-phase`
- `:on-success`
- `:on-fail`
- `:redirect-to`

That left two sources of truth: a lifecycle FSM for status, and an informal
phase-transition engine for the actual run graph. This PR makes the compiled
execution machine the authority for the active workflow run while preserving
current phase semantics.

## Changes In Detail

- Add a compiled execution-machine model in `workflow.fsm`
  - per-phase active states
  - per-phase paused states
  - terminal states
  - event translation for success, failure, retry, already-done, and redirect
- Refactor `workflow.context` so:
  - `:execution/fsm-machine` stores the compiled machine
  - `:execution/fsm-state` stores the authoritative machine snapshot
  - `:execution/status`, `:execution/current-phase`, `:execution/phase-index`,
    and `:execution/redirect-count` are projected from that snapshot
- Refactor `workflow.execution` so phase results become machine events rather
  than direct next-index writes
- Keep existing phase implementations intact for now by translating their
  current result contract into machine events
- Extend workflow validation so the runner validates the machine compilation
  path, not just known phase names
- Add focused FSM tests for:
  - compiled machine projection
  - redirect/retry/pause behavior
  - machine validation

## Testing Plan

- `bb test components/workflow`
- `clj-kondo --lint components/workflow/src/ai/miniforge/workflow/fsm.clj
  components/workflow/src/ai/miniforge/workflow/context.clj components/workflow/src/ai/miniforge/workflow/execution.clj
  components/workflow/src/ai/miniforge/workflow/runner.clj
  components/workflow/src/ai/miniforge/workflow/runner_environment.clj
  components/workflow/test/ai/miniforge/workflow/fsm_test.clj`
- `clojure -M:dev:test -e "(require 'ai.miniforge.phase-software-factory.review-repair-loop-test
  'ai.miniforge.phase-software-factory.verify-failure-modes-test) ..."`

## Deployment Plan

No deployment step. This is a workflow-engine refactor behind the existing
public pipeline API.

## Related Issues/PRs

- Follows `#636`, which formalized the unified execution-machine contract in
  the specs
- Prepares the next slice:
  - phase implementations emitting explicit outcome events instead of steering
    control flow directly
  - supervision/orchestrator integration against the execution machine

## Checklist

- [x] Compiled per-run execution machine added
- [x] Workflow context projections derive from machine snapshot
- [x] Phase transitions run through machine events
- [x] Existing redirect/retry semantics preserved
- [x] Workflow validation covers machine compilation
- [x] Workflow tests pass
